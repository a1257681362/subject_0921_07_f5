package com.evops.workorder.photo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import com.evops.workorder.photo.entity.WorkOrderPhoto;
import com.evops.workorder.photo.mapper.WorkOrderPhotoMapper;
import com.evops.workorder.photo.vo.PhotoView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 作业单现场照片：上传即处理——原图保留（内容寻度命名、CREATE_NEW 落盘、永不覆盖），
 * 同时生成 300px 缩略图到独立 thumb 目录，水印含拍摄时间与机手工号。
 * 同一作业单同名图片只处理一次：先查台账，命中直接返回已登记记录；
 * 并发由 JVM 分段锁串行 + uk_order_photo_name 唯一约束兜底。
 */
@Service
public class WorkOrderPhotoService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderPhotoService.class);

    private static final DateTimeFormatter SHOT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_ACTIVE = "ACTIVE";
    /** 同一 (作业单, 文件名) 的并发上传分段锁，单机内串行，避免重复处理。 */
    private static final int LOCK_STRIPES = 64;

    private final WorkOrderPhotoMapper photoMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderPhotoLedger photoLedger;
    private final ThumbnailProcessor thumbnailProcessor;
    private final PhotoProperties properties;
    private final ReentrantLock[] uploadLocks = new ReentrantLock[LOCK_STRIPES];

    public WorkOrderPhotoService(WorkOrderPhotoMapper photoMapper,
                                 WorkOrderMapper workOrderMapper,
                                 WorkOrderPhotoLedger photoLedger,
                                 ThumbnailProcessor thumbnailProcessor,
                                 PhotoProperties properties) {
        this.photoMapper = photoMapper;
        this.workOrderMapper = workOrderMapper;
        this.photoLedger = photoLedger;
        this.thumbnailProcessor = thumbnailProcessor;
        this.properties = properties;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            uploadLocks[i] = new ReentrantLock();
        }
    }

    /**
     * 上传并处理照片。重复上传同名图（同作业单）不重复处理，返回已登记记录（duplicate=true）。
     */
    public PhotoView upload(Long orderId, MultipartFile file, String shotTimeText,
                            String operatorCode, String requestNo) {
        WorkOrder order = requireOrder(orderId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new BusinessException("图片大小超过限制（" + properties.getMaxFileSize() / 1024 / 1024 + "MB）");
        }
        String operator = requireText(operatorCode, "机手工号不能为空");
        if (operator.length() > 64) {
            throw new BusinessException("机手工号过长（最大64字符）");
        }
        String reqNo = requireText(requestNo, "请求号不能为空");
        LocalDateTime shotTime = parseShotTime(shotTimeText);
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String ext = extensionOf(fileName);

        ReentrantLock lock = lockFor(orderId, fileName);
        lock.lock();
        try {
            WorkOrderPhoto existing = findByOrderAndName(orderId, fileName);
            if (existing != null) {
                // 同名只处理一次：不重复落盘、不覆盖原图，直接返回已登记记录
                return toView(existing, true);
            }

            byte[] bytes = readBytes(file);
            String sha256 = sha256Hex(bytes);
            BufferedImage image = decode(bytes);
            String format = "jpeg".equals(ext) ? "jpg" : ext;
            String watermark = "拍摄时间:" + SHOT_TIME_FORMAT.format(shotTime) + " 机手:" + operator;
            ThumbnailProcessor.Result thumb;
            try {
                thumb = thumbnailProcessor.process(image, properties.getThumbMaxSide(), watermark, format);
            } catch (IOException e) {
                throw new BusinessException("缩略图生成失败");
            }

            // 内容寻址命名：同名即同内容，落盘 CREATE_NEW，已存在即复用，原图永不覆盖
            String storedName = sha256 + "." + ext;
            String originalRel = "original/" + orderId + "/" + storedName;
            String thumbRel = "thumb/" + orderId + "/" + storedName;

            WorkOrderPhoto photo = new WorkOrderPhoto();
            photo.setOrderId(order.getId());
            photo.setOrderNo(order.getOrderNo());
            photo.setFileName(fileName);
            photo.setStoredName(storedName);
            photo.setOriginalPath(originalRel);
            photo.setThumbPath(thumbRel);
            photo.setThumbWidth(thumb.width);
            photo.setThumbHeight(thumb.height);
            photo.setShotTime(shotTime);
            photo.setOperatorCode(operator);
            photo.setFileSize((long) bytes.length);
            photo.setSha256(sha256);
            photo.setStatus(STATUS_ACTIVE);
            photo.setRequestNo(reqNo);

            boolean originalWritten = false;
            boolean thumbWritten = false;
            try {
                originalWritten = writeIfAbsent(originalRel, bytes);
                thumbWritten = writeIfAbsent(thumbRel, thumb.bytes);
                photoLedger.registerAndInsert(photo);
            } catch (DuplicateKeyException e) {
                // 并发下另一笔同名登记先入库：清理我方落盘文件，返回已登记记录
                cleanupIfCreated(originalRel, originalWritten);
                cleanupIfCreated(thumbRel, thumbWritten);
                WorkOrderPhoto winner = findByOrderAndName(orderId, fileName);
                if (winner != null) {
                    return toView(winner, true);
                }
                throw new BusinessException("照片登记冲突，请重试");
            } catch (RuntimeException | IOException e) {
                cleanupIfCreated(originalRel, originalWritten);
                cleanupIfCreated(thumbRel, thumbWritten);
                if (e instanceof IOException) {
                    throw new BusinessException("照片文件写入失败，请检查存储目录");
                }
                throw (RuntimeException) e;
            }
            log.info("作业单 {} 照片登记完成: {} -> {}", order.getOrderNo(), fileName, originalRel);
            return toView(photo, false);
        } finally {
            lock.unlock();
        }
    }

    /** 作业单照片列表（缩略图供列表与报表引用）。 */
    public List<PhotoView> listByOrder(Long orderId) {
        requireOrder(orderId);
        return photoMapper.selectList(new LambdaQueryWrapper<WorkOrderPhoto>()
                        .eq(WorkOrderPhoto::getOrderId, orderId)
                        .orderByAsc(WorkOrderPhoto::getId))
                .stream().map(p -> toView(p, false)).collect(Collectors.toList());
    }

    /** 读取缩略图文件。 */
    public PhotoFile loadThumb(Long photoId) {
        WorkOrderPhoto photo = requirePhoto(photoId);
        return new PhotoFile(readFile(photo.getThumbPath()), contentTypeOf(photo.getStoredName()),
                photo.getFileName());
    }

    /** 读取原图文件（留档查看/下载）。 */
    public PhotoFile loadOriginal(Long photoId) {
        WorkOrderPhoto photo = requirePhoto(photoId);
        return new PhotoFile(readFile(photo.getOriginalPath()), contentTypeOf(photo.getStoredName()),
                photo.getFileName());
    }

    /** 照片文件内容 + 展示信息。 */
    public static class PhotoFile {
        public final byte[] bytes;
        public final String contentType;
        public final String fileName;

        PhotoFile(byte[] bytes, String contentType, String fileName) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.fileName = fileName;
        }
    }

    private WorkOrder requireOrder(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("作业单不存在: " + orderId);
        }
        return order;
    }

    private WorkOrderPhoto requirePhoto(Long photoId) {
        WorkOrderPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("照片不存在: " + photoId);
        }
        return photo;
    }

    private WorkOrderPhoto findByOrderAndName(Long orderId, String fileName) {
        return photoMapper.selectOne(new LambdaQueryWrapper<WorkOrderPhoto>()
                .eq(WorkOrderPhoto::getOrderId, orderId)
                .eq(WorkOrderPhoto::getFileName, fileName));
    }

    private PhotoView toView(WorkOrderPhoto photo, boolean duplicate) {
        PhotoView view = new PhotoView();
        view.setPhotoId(photo.getId());
        view.setOrderId(photo.getOrderId());
        view.setOrderNo(photo.getOrderNo());
        view.setFileName(photo.getFileName());
        view.setShotTime(photo.getShotTime());
        view.setOperatorCode(photo.getOperatorCode());
        view.setFileSize(photo.getFileSize());
        view.setSha256(photo.getSha256());
        view.setThumbWidth(photo.getThumbWidth());
        view.setThumbHeight(photo.getThumbHeight());
        view.setThumbUrl("/api/work-orders/photos/" + photo.getId() + "/thumb");
        view.setOriginalUrl("/api/work-orders/photos/" + photo.getId() + "/original");
        view.setDuplicate(duplicate);
        view.setCreateTime(photo.getCreateTime());
        return view;
    }

    private ReentrantLock lockFor(Long orderId, String fileName) {
        int hash = Math.floorMod((orderId + ":" + fileName).hashCode(), LOCK_STRIPES);
        return uploadLocks[hash];
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private LocalDateTime parseShotTime(String raw) {
        String text = requireText(raw, "拍摄时间不能为空");
        try {
            return LocalDateTime.parse(text, SHOT_TIME_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException e2) {
                throw new BusinessException("拍摄时间格式应为 yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    /** 只保留文件本名（剥掉客户端路径），中文名保留（仅作展示，落盘用内容哈希名）。 */
    private String sanitizeFileName(String raw) {
        if (raw == null) {
            throw new BusinessException("文件名不能为空");
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        if (name.length() > 200) {
            throw new BusinessException("文件名过长（最大200字符）");
        }
        return name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException("图片文件缺少扩展名");
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!properties.getAllowedTypes().contains(ext)) {
            throw new BusinessException("仅支持 " + properties.getAllowedTypes() + " 格式图片");
        }
        return ext;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败");
        }
    }

    private BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new BusinessException("图片内容无法识别，仅支持 JPG/PNG");
            }
            return image;
        } catch (IOException e) {
            throw new BusinessException("图片内容无法识别，仅支持 JPG/PNG");
        }
    }

    /** CREATE_NEW 落盘：已存在（同内容寻址名）即复用，绝不覆盖。返回是否为本次新写。 */
    private boolean writeIfAbsent(String relPath, byte[] bytes) throws IOException {
        Path abs = resolveInRoot(relPath);
        Files.createDirectories(abs.getParent());
        try {
            Files.write(abs, bytes, StandardOpenOption.CREATE_NEW);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    private void cleanupIfCreated(String relPath, boolean createdByUs) {
        if (!createdByUs) {
            return;
        }
        try {
            Files.deleteIfExists(resolveInRoot(relPath));
        } catch (IOException e) {
            log.warn("清理落盘文件失败: {}", relPath, e);
        }
    }

    private byte[] readFile(String relPath) {
        Path abs = resolveInRoot(relPath);
        if (!Files.exists(abs)) {
            throw new BusinessException("照片文件不存在或已丢失: " + relPath);
        }
        try {
            return Files.readAllBytes(abs);
        } catch (IOException e) {
            throw new BusinessException("照片文件读取失败");
        }
    }

    /** 相对路径解析到存储根目录内，防路径穿越。 */
    private Path resolveInRoot(String relPath) {
        Path root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
        Path abs = root.resolve(relPath).normalize();
        if (!abs.startsWith(root)) {
            throw new BusinessException("非法文件路径");
        }
        return abs;
    }

    private String contentTypeOf(String storedName) {
        return storedName.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
