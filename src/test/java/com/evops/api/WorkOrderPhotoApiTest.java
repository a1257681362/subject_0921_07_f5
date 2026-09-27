package com.evops.api;

import com.evops.workorder.photo.PhotoProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作业单现场照片接口：上传处理、同名去重、原图保留、缩略图与水印、列表。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderPhotoApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PhotoProperties photoProperties;

    @BeforeAll
    static void cleanPhotoRoot() throws Exception {
        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "evops-photo-test");
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void upload_generatesThumbAndKeepsOriginal() throws Exception {
        long orderId = createOrder();
        byte[] imageBytes = gradientImage(1200, 800, "jpg");

        MvcResult result = uploadPhoto(orderId, "现场-播种.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.thumbWidth").value(300))
                .andExpect(jsonPath("$.data.thumbHeight").value(200))
                .andExpect(jsonPath("$.data.operatorCode").value("JG-A017"))
                .andReturn();

        String thumbUrl = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.thumbUrl");
        String originalUrl = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.originalUrl");

        // 缩略图：300px 等比
        byte[] thumbBytes = mockMvc.perform(get(thumbUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbBytes));
        assertEquals(300, thumb.getWidth());
        assertEquals(200, thumb.getHeight());

        // 原图：字节级一致，永不覆盖
        byte[] originalBytes = mockMvc.perform(get(originalUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(imageBytes, originalBytes, "原图必须与上传字节一致");

        // 落盘：original/ 与 thumb/ 独立目录各一份
        Path originalDir = photoRoot().resolve("original/" + orderId);
        Path thumbDir = photoRoot().resolve("thumb/" + orderId);
        assertEquals(1, Files.list(originalDir).count(), "原图目录恰好一份");
        assertEquals(1, Files.list(thumbDir).count(), "缩略图目录恰好一份");
    }

    @Test
    void duplicateSameName_processedOnlyOnce() throws Exception {
        long orderId = createOrder();
        byte[] imageBytes = gradientImage(900, 600, "jpg");

        MvcResult first = uploadPhoto(orderId, "重复.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn();
        long photoId = ((Number) com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.data.photoId")).longValue();

        // 重复上传同名图（不同请求号）→ 只处理一次，返回已登记记录
        uploadPhoto(orderId, "重复.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.photoId").value(photoId));

        // 台账只有一条、磁盘只有一份
        mockMvc.perform(get("/api/work-orders/{id}/photos", orderId))
                .andExpect(jsonPath("$.data.length()").value(1));
        assertEquals(1, Files.list(photoRoot().resolve("original/" + orderId)).count());
        assertEquals(1, Files.list(photoRoot().resolve("thumb/" + orderId)).count());
    }

    @Test
    void sameNameOnDifferentOrders_bothProcessed() throws Exception {
        long orderA = createOrder();
        long orderB = createOrder();
        byte[] imageBytes = gradientImage(800, 600, "jpg");

        uploadPhoto(orderA, "同名.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.data.duplicate").value(false));
        uploadPhoto(orderB, "同名.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.data.duplicate").value(false));

        mockMvc.perform(get("/api/work-orders/{id}/photos", orderA))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/work-orders/{id}/photos", orderB))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void sameContentDifferentName_storageReusedNotDuplicated() throws Exception {
        long orderId = createOrder();
        byte[] imageBytes = gradientImage(640, 480, "jpg");

        uploadPhoto(orderId, "名字一.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.data.duplicate").value(false));
        uploadPhoto(orderId, "名字二.jpg", imageBytes, "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.data.duplicate").value(false));

        // 两条台账（不同文件名），但内容寻址落盘只有一份原图、一份缩略图
        mockMvc.perform(get("/api/work-orders/{id}/photos", orderId))
                .andExpect(jsonPath("$.data.length()").value(2));
        assertEquals(1, Files.list(photoRoot().resolve("original/" + orderId)).count());
        assertEquals(1, Files.list(photoRoot().resolve("thumb/" + orderId)).count());
    }

    @Test
    void invalidFileType_rejected() throws Exception {
        long orderId = createOrder();
        MockMultipartFile file = new MockMultipartFile("file", "说明.txt", "text/plain", "不是图片".getBytes());
        mockMvc.perform(multipart("/api/work-orders/{id}/photos", orderId).file(file)
                        .param("shotTime", "2026-09-27 10:30:00")
                        .param("operatorCode", "JG-A017")
                        .param("requestNo", "REQ-PH-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void badShotTime_rejected() throws Exception {
        long orderId = createOrder();
        MockMultipartFile file = new MockMultipartFile("file", "现场.jpg", "image/jpeg", gradientImage(100, 100, "jpg"));
        mockMvc.perform(multipart("/api/work-orders/{id}/photos", orderId).file(file)
                        .param("shotTime", "昨天下午")
                        .param("operatorCode", "JG-A017")
                        .param("requestNo", "REQ-PH-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("拍摄时间")));
    }

    @Test
    void replayRequestNo_rejected() throws Exception {
        long orderId = createOrder();
        String requestNo = "REQ-PH-" + SEQ.incrementAndGet();
        uploadPhoto(orderId, "第一张.jpg", gradientImage(100, 100, "jpg"), requestNo)
                .andExpect(jsonPath("$.success").value(true));

        // 同一请求号换文件名重放 → 台账防重拒绝
        uploadPhoto(orderId, "第二张.jpg", gradientImage(100, 100, "jpg"), requestNo)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已处理")));
    }

    @Test
    void orderNotFound_rejected() throws Exception {
        uploadPhoto(999999L, "现场.jpg", gradientImage(100, 100, "jpg"), "REQ-PH-" + SEQ.incrementAndGet())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("作业单不存在")));
    }

    // ---- 辅助 ----

    private Path photoRoot() {
        return Paths.get(photoProperties.getRootDir()).toAbsolutePath().normalize();
    }

    private long createOrder() throws Exception {
        String machineCode = "M-PH-" + SEQ.incrementAndGet();
        mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineCode\":\"" + machineCode + "\",\"machineName\":\"照片测试机\","
                                + "\"requestNo\":\"REQ-PH-M-" + SEQ.incrementAndGet() + "\"}"))
                .andExpect(jsonPath("$.success").value(true));
        String listJson = mockMvc.perform(get("/api/machines")).andReturn().getResponse().getContentAsString();
        java.util.List<Object> ids = com.jayway.jsonpath.JsonPath.read(listJson,
                "$.data[?(@.machineCode=='" + machineCode + "')].id");
        long machineId = ((Number) ids.get(0)).longValue();

        String orderNo = "WO-PH-" + SEQ.incrementAndGet();
        MvcResult result = mockMvc.perform(post("/api/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"machineId\":" + machineId
                                + ",\"operationType\":\"播种\",\"requestNo\":\"REQ-PH-O-" + SEQ.incrementAndGet() + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions uploadPhoto(long orderId, String fileName,
                                                                           byte[] bytes, String requestNo) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "image/jpeg", bytes);
        return mockMvc.perform(multipart("/api/work-orders/{id}/photos", orderId).file(file)
                .param("shotTime", "2026-09-27 10:30:00")
                .param("operatorCode", "JG-A017")
                .param("requestNo", requestNo));
    }

    /** 生成渐变测试图（纯色图压缩后太小，不像真实照片）。 */
    private byte[] gradientImage(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = 0; x < width; x++) {
            g.setColor(new Color((x * 255) / width, (x * 128) / width % 256, 200));
            g.drawLine(x, 0, x, height);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
