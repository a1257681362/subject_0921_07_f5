package com.evops.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 结算接口层：统一返回结构、入口必填校验、生成→流转→详情闭环。独立内存库隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_settlement_api_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generate_missingFields_returnsUnifiedFail() throws Exception {
        mockMvc.perform(post("/api/settlements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不能为空")));
    }

    @Test
    void transit_missingVersionOrOperator_returnsUnifiedFail() throws Exception {
        // 只给目标状态，缺版本与操作人：入口统一校验拒绝
        mockMvc.perform(post("/api/settlements/1/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不能为空")));
    }

    @Test
    void generateTransitDetail_closedLoop() throws Exception {
        // 准备：农机 + 完工作业单
        Long machineId = createMachine();
        Long orderId = createWorkOrder(machineId);
        postJson("/api/work-orders/" + orderId + "/track-area",
                "{\"trackArea\":12.00,\"requestNo\":\"" + uniq("REQ-API-TA-") + "\"}", true);
        postJson("/api/work-orders/" + orderId + "/transit",
                "{\"targetStatus\":\"IN_PROGRESS\",\"requestNo\":\"" + uniq("REQ-API-TR-") + "\"}", true);
        postJson("/api/work-orders/" + orderId + "/transit",
                "{\"targetStatus\":\"COMPLETED\",\"requestNo\":\"" + uniq("REQ-API-TR-") + "\"}", true);

        // 生成结算单
        String gen = postJson("/api/settlements/generate",
                "{\"period\":\"2027-09\",\"unitPrice\":85.00,\"operator\":\"财务-接口\","
                        + "\"requestNo\":\"" + uniq("REQ-API-G-") + "\"}", true);
        Long settlementId = ((Number) JsonPath.read(gen, "$.data.id")).longValue();
        Number version = JsonPath.read(gen, "$.data.version");

        // 非法迁移：DRAFT -> PAID 跳级，拒绝
        postJson("/api/settlements/" + settlementId + "/transit",
                "{\"targetStatus\":\"PAID\",\"version\":" + version + ",\"operator\":\"财务-接口\","
                        + "\"requestNo\":\"" + uniq("REQ-API-T-") + "\"}", false);

        // 合法迁移：DRAFT -> CONFIRMED，带版本与操作人
        String confirmed = postJson("/api/settlements/" + settlementId + "/transit",
                "{\"targetStatus\":\"CONFIRMED\",\"version\":" + version + ",\"operator\":\"财务-接口\","
                        + "\"requestNo\":\"" + uniq("REQ-API-T-") + "\"}", true);
        Number newVersion = JsonPath.read(confirmed, "$.data.version");

        // 详情：明细快照 + 流转历史（两条，带版本与操作人）
        mockMvc.perform(get("/api/settlements/" + settlementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settlement.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(1020.00))
                .andExpect(jsonPath("$.data.statusLogs.length()").value(2))
                .andExpect(jsonPath("$.data.statusLogs[1].operator").value("财务-接口"))
                .andExpect(jsonPath("$.data.statusLogs[1].fromVersion").value(version.intValue()))
                .andExpect(jsonPath("$.data.statusLogs[1].toVersion").value(newVersion.intValue()));

        // 迁移规则配置可查
        mockMvc.perform(get("/api/settlements/transitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(7));
    }

    private Long createMachine() throws Exception {
        String response = postJson("/api/machines",
                "{\"machineCode\":\"" + uniq("M-API-ST-") + "\",\"machineName\":\"结算接口机\","
                        + "\"requestNo\":\"" + uniq("REQ-API-M-") + "\"}", true);
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private Long createWorkOrder(Long machineId) throws Exception {
        String response = postJson("/api/work-orders",
                "{\"orderNo\":\"" + uniq("WO-API-ST-") + "\",\"machineId\":" + machineId
                        + ",\"operationType\":\"收割\",\"requestNo\":\"" + uniq("REQ-API-W-") + "\"}", true);
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private String postJson(String path, String body, boolean expectSuccess) throws Exception {
        org.springframework.test.web.servlet.ResultActions actions = mockMvc
                .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(expectSuccess));
        return actions.andReturn().getResponse().getContentAsString();
    }

    private String uniq(String prefix) {
        return prefix + System.nanoTime();
    }
}
