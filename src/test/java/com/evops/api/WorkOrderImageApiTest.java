package com.evops.api;

import com.evops.workorder.image.ImageProcessorTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 作业单图片接口层：上传生成 300px 水印缩略图、原图保留不覆盖、同名重复上传只处理一次、
 * 列表/详情带缩略图地址。落盘目录为 test profile 的 ./target/test-work-order-images。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderImageApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Path STORE_DIR = Paths.get("./target/test-work-order-images");

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanStore() throws Exception {
        if (Files.exists(STORE_DIR)) {
            Files.walk(STORE_DIR).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void upload_generatesWatermarkedThumb_andOriginalPreserved() throws Exception {
        OrderHandle order = prepareOrder();
        byte[] photo = ImageProcessorTest.solidImage(900, 600, "jpg", Color.GREEN);

        MvcResult uploaded = mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", "field-a.jpg", "image/jpeg", photo))
                        .param("operatorCode", "JH0231")
                        .param("capturedAt", "2026-09-26 15:30:00")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.data.image.fileName").value("field-a.jpg"))
                .andExpect(jsonPath("$.data.image.operatorCode").value("JH0231"))
                .andExpect(jsonPath("$.data.image.capturedAt").value("2026-09-26T15:30:00"))
                .andExpect(jsonPath("$.data.image.width").value(900))
                .andExpect(jsonPath("$.data.image.height").value(600))
                .andExpect(jsonPath("$.data.image.thumbWidth").value(300))
                .andExpect(jsonPath("$.data.image.thumbHeight").value(200))
                .andReturn();

        String body = uploaded.getResponse().getContentAsString();
        String thumbUrl = JsonPath.read(body, "$.data.image.thumbUrl");
        String originalUrl = JsonPath.read(body, "$.data.image.originalUrl");

        // 缩略图：可解码、最长边 300
        byte[] thumbBytes = mockMvc.perform(get(thumbUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        java.awt.image.BufferedImage thumb = ImageProcessorTest.decode(thumbBytes);
        assertEquals(300, thumb.getWidth());
        assertEquals(200, thumb.getHeight());

        // 原图：下载字节与上传字节完全一致——原图保留、未被改写
        byte[] originalBytes = mockMvc.perform(get(originalUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(photo, originalBytes, "原图必须与上传字节一致，永不覆盖");

        // 列表与详情都带缩略图地址（列表与报表取数口）
        mockMvc.perform(get("/api/work-orders/{id}/images", order.id))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].thumbUrl").value(thumbUrl));
        mockMvc.perform(get("/api/work-orders").param("orderNo", order.orderNo))
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.images[0].thumbUrl").value(thumbUrl));
    }

    @Test
    void duplicateSameName_processedOnlyOnce() throws Exception {
        OrderHandle order = prepareOrder();
        byte[] photo = ImageProcessorTest.solidImage(800, 600, "jpg", Color.RED);
        String fileName = "repeat-shot.jpg";

        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", fileName, "image/jpeg", photo))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(false));

        // 同名同内容重复上传（换新请求号）：幂等返回首次记录，不重复处理
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", fileName, "image/jpeg", photo))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(true));

        mockMvc.perform(get("/api/work-orders/{id}/images", order.id))
                .andExpect(jsonPath("$.data.length()").value(1));

        // 落盘原图与缩略图各一份（内容寻址，不重复写）
        assertEquals(1, countFiles(STORE_DIR.resolve("original/" + order.id)), "原图只落盘一份");
        assertEquals(1, countFiles(STORE_DIR.resolve("thumbs/" + order.id)), "缩略图只生成一份");
    }

    @Test
    void sameNameDifferentContent_rejected_originalUntouched() throws Exception {
        OrderHandle order = prepareOrder();
        byte[] first = ImageProcessorTest.solidImage(800, 600, "jpg", Color.BLUE);
        byte[] second = ImageProcessorTest.solidImage(800, 600, "jpg", Color.YELLOW);
        String fileName = "clash.jpg";

        MvcResult uploaded = mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", fileName, "image/jpeg", first))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String originalUrl = JsonPath.read(
                uploaded.getResponse().getContentAsString(), "$.data.image.originalUrl");

        // 同名不同内容：拒绝，明确提示原图保留不覆盖
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", fileName, "image/jpeg", second))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不覆盖")));

        // 原图仍是第一次上传的字节
        byte[] originalBytes = mockMvc.perform(get(originalUrl))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(first, originalBytes, "被拒绝的第二次上传不得触碰原图");
        mockMvc.perform(get("/api/work-orders/{id}/images", order.id))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void validation_missingOperator_badExtension_replayedRequestNo() throws Exception {
        OrderHandle order = prepareOrder();
        byte[] photo = ImageProcessorTest.solidImage(400, 300, "jpg", Color.GRAY);

        // 缺机手工号 → 参数异常，统一 success=false
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", "no-operator.jpg", "image/jpeg", photo))
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("operatorCode")));

        // 不支持的扩展名
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", photo))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", "REQ-IMG-" + SEQ.incrementAndGet()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不支持")));

        // 请求号重放 → 台账拒绝
        String requestNo = "REQ-IMG-REPLAY-" + SEQ.incrementAndGet();
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", "replay-a.jpg", "image/jpeg", photo))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", requestNo))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(multipart("/api/work-orders/{id}/images", order.id)
                        .file(new MockMultipartFile("file", "replay-b.jpg", "image/jpeg", photo))
                        .param("operatorCode", "JH0231")
                        .param("requestNo", requestNo))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("重复提交")));
    }

    private long countFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    /** 建农机 + 作业单，返回作业单 ID 与单号。 */
    private OrderHandle prepareOrder() throws Exception {
        int seq = SEQ.incrementAndGet();
        MvcResult machine = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineCode\":\"M-IMG-" + seq + "\",\"machineName\":\"图片测试机\","
                                + "\"requestNo\":\"REQ-IMG-M-" + seq + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        Number machineId = JsonPath.read(machine.getResponse().getContentAsString(), "$.data.id");

        String orderNo = "WO-IMG-" + seq;
        MvcResult order = mockMvc.perform(post("/api/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"machineId\":" + machineId
                                + ",\"operationType\":\"植保\",\"requestNo\":\"REQ-IMG-W-" + seq + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        Number orderId = JsonPath.read(order.getResponse().getContentAsString(), "$.data.id");
        return new OrderHandle(orderId.longValue(), orderNo);
    }

    private static final class OrderHandle {
        final long id;
        final String orderNo;

        OrderHandle(long id, String orderNo) {
            this.id = id;
            this.orderNo = orderNo;
        }
    }
}
