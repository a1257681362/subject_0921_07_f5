package com.evops.workorder.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.config.WorkOrderImageProperties;
import com.evops.request.RequestRecordService;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.image.entity.WorkOrderImage;
import com.evops.workorder.image.mapper.WorkOrderImageMapper;
import com.evops.workorder.image.vo.ImageUploadResult;
import com.evops.workorder.image.vo.WorkOrderImageView;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作业单图片处理。口径：
 * <ul>
 *   <li>上传即生成 300px 缩略图（底部水印：拍摄时间 + 机手工号），原图与缩略图分目录落盘；</li>
 *   <li>原图永不覆盖：存储名取内容 SHA-256，同名不同内容直接拒绝，重复上传只处理一次；</li>
 *   <li>重复上传同名图：同内容幂等返回首次记录（duplicated=true），不重复处理、不写盘；</li>
 *   <li>请求号台账与图片元数据落库同一事务；文件先于落库写盘（内容寻址，失败残留可复用）。</li>
 * </ul>
 */
@Service
public class WorkOrderImageService {

    private static final Set<String> ALLOWED_EXTENSIONS =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "bmp", "gif"));
    private static final int MAX_FILE_NAME_LENGTH = 120;
    private static final int MAX_OPERATOR_CODE_LENGTH = 24;
    private static final DateTimeFormatter WATERMARK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkOrderImageMapper imageMapper;
    private final WorkOrderMapper workOrderMapper;
    private final RequestRecordService requestRecordService;
    private final WorkOrderImageProperties properties;

    public WorkOrderImageService(WorkOrderImageMapper imageMapper,
                                 WorkOrderMapper workOrderMapper,
                                 RequestRecordService requestRecordService,
                                 WorkOrderImageProperties properties) {
        this.imageMapper = imageMapper;
        this.workOrderMapper = workOrderMapper;
        this.requestRecordService = requestRecordService;
        this.properties = properties;
    }

    /**
     * 上传作业单图片：校验 → 去重 → 生成水印缩略图 → 原图/缩略图落盘 → 台账+元数据同事务落库。
     * 并发同名上传由 uk_work_order_image_name 兜底：后到者按重复上传幂等返回（H2 约束冲突
     * 不中止事务，捕获后可继续查询；换库时需复核该假设）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImageUploadResult upload(Long orderId, MultipartFile file,
                                    String operatorCode, String capturedAtText, String requestNo) {
        WorkOrder order = requireOrder(orderId);
        String fileName = sanitizeFileName(file);
        String extension = extensionOf(fileName);
        String operator = requireOperatorCode(operatorCode);
        LocalDateTime capturedAt = parseCapturedAt(capturedAtText);

        byte[] bytes = readBytes(file);
        String sha256 = sha256Hex(bytes);

        WorkOrderImage existing = findByOrderAndName(orderId, fileName);
        if (existing != null) {
            return duplicateVerdict(existing, sha256, fileName);
        }

        String watermark = capturedAt.format(WATERMARK_TIME) + " " + operator;
        String format = ImageProcessor.normalizeFormat(extension);
        ImageProcessor.Result processed =
                ImageProcessor.makeThumbnail(bytes, format, properties.getThumbSize(), watermark);

        String storedName = sha256 + "." + format;
        String originalRel = "original/" + orderId + "/" + storedName;
        String thumbRel = "thumbs/" + orderId + "/" + storedName;
        writeContentAddressed(originalRel, bytes);
        writeContentAddressed(thumbRel, processed.getThumbBytes());

        requestRecordService.register(requestNo, BizType.WORK_ORDER_IMAGE_UPLOAD, orderId + ":" + fileName);

        WorkOrderImage image = new WorkOrderImage();
        image.setOrderId(order.getId());
        image.setOrderNo(order.getOrderNo());
        image.setFileName(fileName);
        image.setStoredName(storedName);
        image.setFileSha256(sha256);
        image.setFileSize((long) bytes.length);
        image.setMediaType(mediaTypeOf(format));
        image.setWidth(processed.getSourceWidth());
        image.setHeight(processed.getSourceHeight());
        image.setThumbWidth(processed.getThumbWidth());
        image.setThumbHeight(processed.getThumbHeight());
        image.setCapturedAt(capturedAt);
        image.setOperatorCode(operator);
        image.setOriginalPath(originalRel);
        image.setThumbPath(thumbRel);
        image.setRequestNo(requestNo);
        try {
            imageMapper.insert(image);
        } catch (DuplicateKeyException e) {
            // 并发同名上传：唯一约束只放行一笔，本笔按重复上传口径返回先到者的记录
            WorkOrderImage winner = findByOrderAndName(orderId, fileName);
            if (winner == null) {
                throw new BusinessException("图片登记冲突，请重试");
            }
            return duplicateVerdict(winner, sha256, fileName);
        }
        return ImageUploadResult.of(WorkOrderImageView.of(image), false);
    }

    /** 作业单图片列表（带缩略图地址，供列表与报表使用）。 */
    public List<WorkOrderImageView> listForOrder(Long orderId) {
        requireOrder(orderId);
        return listViews(orderId);
    }

    /** 详情装配用：按作业单 ID 取图片视图，不校验作业单存在性（调用方已校验）。 */
    public List<WorkOrderImageView> listViews(Long orderId) {
        return imageMapper.selectList(new LambdaQueryWrapper<WorkOrderImage>()
                        .eq(WorkOrderImage::getOrderId, orderId)
                        .orderByAsc(WorkOrderImage::getId))
                .stream().map(WorkOrderImageView::of).collect(Collectors.toList());
    }

    /** 读取缩略图字节（列表与报表一律走这里）。 */
    public ImageContent loadThumb(Long imageId) {
        return load(imageId, true);
    }

    /** 读取原图字节（仅详情备查；原图自落盘起从未被改写）。 */
    public ImageContent loadOriginal(Long imageId) {
        return load(imageId, false);
    }

    /** 图片字节 + 展示所需的媒体类型与存储名。 */
    public static final class ImageContent {
        private final byte[] bytes;
        private final String mediaType;
        private final String storedName;

        ImageContent(byte[] bytes, String mediaType, String storedName) {
            this.bytes = bytes;
            this.mediaType = mediaType;
            this.storedName = storedName;
        }

        public byte[] getBytes() { return bytes; }
        public String getMediaType() { return mediaType; }
        public String getStoredName() { return storedName; }
    }

    private ImageContent load(Long imageId, boolean thumb) {
        WorkOrderImage image = imageMapper.selectById(imageId);
        if (image == null) {
            throw new BusinessException("图片不存在: " + imageId);
        }
        String rel = thumb ? image.getThumbPath() : image.getOriginalPath();
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(properties.getRootDir()).resolve(rel).normalize());
            return new ImageContent(bytes, image.getMediaType(), image.getStoredName());
        } catch (IOException e) {
            throw new BusinessException("图片文件缺失或不可读，请联系管理员核查存储目录");
        }
    }

    /** 重复上传判定：同内容幂等返回首次记录；同名不同内容拒绝，原图保留不覆盖。 */
    private ImageUploadResult duplicateVerdict(WorkOrderImage existing, String sha256, String fileName) {
        if (existing.getFileSha256().equals(sha256)) {
            return ImageUploadResult.of(WorkOrderImageView.of(existing), true);
        }
        throw new BusinessException("作业单下已存在同名图片且内容不同，原图保留不覆盖；请改名后上传: " + fileName);
    }

    private WorkOrder requireOrder(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("作业单不存在: " + orderId);
        }
        return order;
    }

    private WorkOrderImage findByOrderAndName(Long orderId, String fileName) {
        return imageMapper.selectOne(new LambdaQueryWrapper<WorkOrderImage>()
                .eq(WorkOrderImage::getOrderId, orderId)
                .eq(WorkOrderImage::getFileName, fileName));
    }

    /** 文件名只作元数据：剥离路径成分防穿越，落盘路径一律用服务端生成的存储名。 */
    private String sanitizeFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传图片为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("文件名为空");
        }
        name = name.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty()) {
            throw new BusinessException("文件名为空");
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException("文件名过长（超过 " + MAX_FILE_NAME_LENGTH + " 字符）: " + name);
        }
        return name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException("图片缺少扩展名，仅支持 jpg/png/bmp/gif: " + fileName);
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的图片格式 ." + ext + "，仅支持 jpg/png/bmp/gif");
        }
        return ext;
    }

    private String requireOperatorCode(String operatorCode) {
        if (operatorCode == null || operatorCode.trim().isEmpty()) {
            throw new BusinessException("机手工号不能为空（水印要素）");
        }
        String operator = operatorCode.trim();
        if (operator.length() > MAX_OPERATOR_CODE_LENGTH) {
            throw new BusinessException("机手工号过长（超过 " + MAX_OPERATOR_CODE_LENGTH + " 字符）");
        }
        return operator;
    }

    /** 拍摄时间：上传方可传 "yyyy-MM-dd HH:mm:ss" 或 ISO 格式；缺省取服务端接收时间。 */
    private LocalDateTime parseCapturedAt(String capturedAtText) {
        if (capturedAtText == null || capturedAtText.trim().isEmpty()) {
            return LocalDateTime.now();
        }
        String text = capturedAtText.trim();
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // 继续尝试空格分隔格式
        }
        try {
            return LocalDateTime.parse(text, WATERMARK_TIME);
        } catch (DateTimeParseException e) {
            throw new BusinessException("拍摄时间格式不正确，应为 yyyy-MM-dd HH:mm:ss: " + text);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传图片失败: " + e.getMessage());
        }
    }

    /** 内容寻址写盘：CREATE_NEW 已存在即跳过（同 SHA-256 必同内容），原图永不覆盖。 */
    private void writeContentAddressed(String relativePath, byte[] bytes) {
        Path path = Paths.get(properties.getRootDir()).resolve(relativePath).normalize();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            // 内容寻址：同名即同内容，跳过不写
        } catch (IOException e) {
            throw new BusinessException("图片写盘失败: " + e.getMessage());
        }
    }

    private String mediaTypeOf(String format) {
        switch (format) {
            case "jpg": return "image/jpeg";
            case "png": return "image/png";
            case "bmp": return "image/bmp";
            case "gif": return "image/gif";
            default: return "application/octet-stream";
        }
    }

    private String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
