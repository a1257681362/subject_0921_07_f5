package com.evops.settlement;

import com.evops.common.BusinessException;
import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.entity.Machine;
import com.evops.settlement.dto.SettlementGenerateRequest;
import com.evops.settlement.dto.SettlementTransitRequest;
import com.evops.settlement.dto.TransitionToggleRequest;
import com.evops.settlement.entity.Settlement;
import com.evops.settlement.entity.SettlementStatusLog;
import com.evops.settlement.entity.SettlementTransition;
import com.evops.settlement.vo.SettlementDetail;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算单主链路：生成 → 状态机流转 → 版本与操作人 → 流转历史 → 作废释放。
 * 独立内存库（evops_settlement_test）与其他用例隔离；每个用例结算完自己建的完工单，
 * 不遗留可结算数据，保证用例间互不影响。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_settlement_test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class SettlementFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MachineService machineService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private SettlementService settlementService;

    @Test
    void generateSnapshotsCompletedOrders_andSettlesOnlyOnce() {
        Machine machine = createMachine();
        WorkOrder o1 = completeOrder(machine.getId(), "WO-ST-A1-", "10.00");
        WorkOrder o2 = completeOrder(machine.getId(), "WO-ST-A2-", "5.50");
        // 未完工的单不进结算
        createOrder(machine.getId(), "WO-ST-A3-");

        Settlement settlement = settlementService.generate(
                generateReq(uniqPeriod(), "80.00", "财务-小王"));

        assertEquals(SettlementStatus.DRAFT, settlement.getStatus());
        assertEquals(2, settlement.getOrderCount());
        assertEquals(0, new BigDecimal("15.50").compareTo(settlement.getTotalArea()));
        assertEquals(0, new BigDecimal("1240.00").compareTo(settlement.getTotalAmount()));
        assertEquals(0, settlement.getVersion());

        SettlementDetail detail = settlementService.detail(settlement.getId());
        assertEquals(2, detail.getItems().size());
        assertTrue(detail.getItems().stream().anyMatch(i -> i.getOrderId().equals(o1.getId())));
        assertTrue(detail.getItems().stream().anyMatch(i -> i.getOrderId().equals(o2.getId())));
        // 明细即结算快照：面积、单价、金额都落库，无需二次加工
        assertTrue(detail.getItems().stream().allMatch(i -> i.getAmount() != null
                && i.getUnitPrice() != null && i.getTrackArea() != null));
        // 建档即第一条流转历史
        assertEquals(1, detail.getStatusLogs().size());
        assertEquals(SettlementStatus.DRAFT, detail.getStatusLogs().get(0).getToStatus());
        assertEquals("财务-小王", detail.getStatusLogs().get(0).getOperator());
        assertEquals(Integer.valueOf(0), detail.getStatusLogs().get(0).getToVersion());

        // 同一批作业单不能重复结算
        BusinessException again = assertThrows(BusinessException.class, () ->
                settlementService.generate(generateReq(uniqPeriod(), "80.00", "财务-小王")));
        assertTrue(again.getMessage().contains("没有可结算"));
    }

    @Test
    void duplicateActivePeriodRejected() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), "WO-ST-B1-");
        String period = uniqPeriod();
        Settlement first = settlementService.generate(generateReq(period, "80.00", "财务-小王"));

        // 期间已有有效结算单，即使有新的完工单也不允许再生成同期间的
        completeOrder(machine.getId(), "WO-ST-B2-");
        BusinessException dup = assertThrows(BusinessException.class, () ->
                settlementService.generate(generateReq(period, "80.00", "财务-小王")));
        assertTrue(dup.getMessage().contains("不能重复生成"));

        // 清理：作废首单释放期间与作业单占用，重新生成把两单都结算掉，不留可结算数据
        settlementService.transit(first.getId(),
                transitReq(SettlementStatus.CANCELLED, 0, "财务-小王", "用例清理"));
        Settlement second = settlementService.generate(generateReq(period, "80.00", "财务-小王"));
        assertEquals(2, second.getOrderCount());
    }

    @Test
    void transitFollowsConfiguredRules_andCarriesVersionAndOperator() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), "WO-ST-C1-");
        Settlement settlement = settlementService.generate(
                generateReq(uniqPeriod(), "100.00", "财务-小王"));
        Long id = settlement.getId();

        // 完整链路：DRAFT -> CONFIRMED -> APPROVED -> PAID，每步版本 +1、操作人留痕
        Settlement confirmed = settlementService.transit(id,
                transitReq(SettlementStatus.CONFIRMED, 0, "财务-小王", null));
        assertEquals(SettlementStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(1, confirmed.getVersion());

        Settlement approved = settlementService.transit(id,
                transitReq(SettlementStatus.APPROVED, 1, "合作社-老李", null));
        assertEquals(2, approved.getVersion());

        Settlement paid = settlementService.transit(id,
                transitReq(SettlementStatus.PAID, 2, "出纳-小赵", "作业费已转账"));
        assertEquals(SettlementStatus.PAID, paid.getStatus());
        assertEquals(3, paid.getVersion());

        // 流转历史完整：建档 + 三次流转，每步带前后版本与操作人
        SettlementDetail detail = settlementService.detail(id);
        assertEquals(4, detail.getStatusLogs().size());
        assertLog(detail, 1, "DRAFT", "CONFIRMED", 0, 1, "财务-小王");
        assertLog(detail, 2, "CONFIRMED", "APPROVED", 1, 2, "合作社-老李");
        assertLog(detail, 3, "APPROVED", "PAID", 2, 3, "出纳-小赵");
    }

    @Test
    void illegalTransitRejected_withAllowedTargetsInMessage() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), "WO-ST-D1-");
        Settlement settlement = settlementService.generate(
                generateReq(uniqPeriod(), "100.00", "财务-小王"));
        Long id = settlement.getId();

        // 跳级：DRAFT -> APPROVED 不允许，报错给出当前可去方向
        BusinessException skip = assertThrows(BusinessException.class, () ->
                settlementService.transit(id, transitReq(SettlementStatus.APPROVED, 0, "财务-小王", null)));
        assertTrue(skip.getMessage().contains("不允许"));
        assertTrue(skip.getMessage().contains("CONFIRMED"), "报错应给出当前可去方向: " + skip.getMessage());

        // 未知状态
        assertThrows(BusinessException.class, () ->
                settlementService.transit(id, transitReq("FOO", 0, "财务-小王", null)));

        // 走到终态后任何流转都拒绝
        settlementService.transit(id, transitReq(SettlementStatus.CONFIRMED, 0, "财务-小王", null));
        settlementService.transit(id, transitReq(SettlementStatus.APPROVED, 1, "合作社-老李", null));
        settlementService.transit(id, transitReq(SettlementStatus.PAID, 2, "出纳-小赵", null));
        BusinessException terminal = assertThrows(BusinessException.class, () ->
                settlementService.transit(id, transitReq(SettlementStatus.CONFIRMED, 3, "财务-小王", null)));
        assertTrue(terminal.getMessage().contains("终态"));
    }

    @Test
    void staleVersionRejected_andRetryWithSameRequestNoWorks() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), "WO-ST-E1-");
        Settlement settlement = settlementService.generate(
                generateReq(uniqPeriod(), "100.00", "财务-小王"));
        Long id = settlement.getId();

        String requestNo = uniq("REQ-ST-T-");
        SettlementTransitRequest stale = transitReq(SettlementStatus.CONFIRMED, 9, "财务-小王", null);
        stale.setRequestNo(requestNo);
        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> settlementService.transit(id, stale));
        assertTrue(mismatch.getMessage().contains("版本"));

        // 版本校验失败整体回滚，请求号未消耗；改正版本后同一请求号可重试成功
        SettlementTransitRequest retry = transitReq(SettlementStatus.CONFIRMED, 0, "财务-小王", null);
        retry.setRequestNo(requestNo);
        Settlement ok = settlementService.transit(id, retry);
        assertEquals(SettlementStatus.CONFIRMED, ok.getStatus());
    }

    @Test
    void disabledRuleRejectsTransit_untilReenabled() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), "WO-ST-F1-");
        Settlement settlement = settlementService.generate(
                generateReq(uniqPeriod(), "100.00", "财务-小王"));

        // 找到 DRAFT -> CONFIRMED 规则并停用（配置化，无需改代码）
        SettlementTransition rule = findRule(SettlementStatus.DRAFT, SettlementStatus.CONFIRMED);
        settlementService.toggleTransition(rule.getId(), toggleReq(false, rule.getVersion(), "管理员-小周"));
        try {
            BusinessException disabled = assertThrows(BusinessException.class, () ->
                    settlementService.transit(settlement.getId(),
                            transitReq(SettlementStatus.CONFIRMED, 0, "财务-小王", null)));
            assertTrue(disabled.getMessage().contains("停用"));

            // 规则变更同样带版本乐观锁，旧版本拒绝
            assertThrows(BusinessException.class, () ->
                    settlementService.toggleTransition(rule.getId(),
                            toggleReq(true, rule.getVersion(), "管理员-小周")));
        } finally {
            // 重新启用
            SettlementTransition fresh = findRule(SettlementStatus.DRAFT, SettlementStatus.CONFIRMED);
            settlementService.toggleTransition(rule.getId(), toggleReq(true, fresh.getVersion(), "管理员-小周"));
        }
        Settlement ok = settlementService.transit(settlement.getId(),
                transitReq(SettlementStatus.CONFIRMED, 0, "财务-小王", null));
        assertEquals(SettlementStatus.CONFIRMED, ok.getStatus());
    }

    @Test
    void cancelRequiresReason_andReleasesOrdersForResettlement() {
        Machine machine = createMachine();
        WorkOrder order = completeOrder(machine.getId(), "WO-ST-G1-");
        String period = uniqPeriod();
        Settlement settlement = settlementService.generate(generateReq(period, "100.00", "财务-小王"));
        Long id = settlement.getId();

        // 作废必须填原因
        assertThrows(BusinessException.class, () ->
                settlementService.transit(id, transitReq(SettlementStatus.CANCELLED, 0, "财务-小王", "  ")));

        Settlement cancelled = settlementService.transit(id,
                transitReq(SettlementStatus.CANCELLED, 0, "财务-小王", "单价录错，作废重开"));
        assertEquals(SettlementStatus.CANCELLED, cancelled.getStatus());

        // 作废后历史保留（含作废原因）
        SettlementDetail detail = settlementService.detail(id);
        assertEquals(2, detail.getStatusLogs().size());
        assertEquals("单价录错，作废重开", detail.getStatusLogs().get(1).getRemark());

        // 作业单与期间都释放，可重新生成；单号序号不复用
        Settlement regenerated = settlementService.generate(generateReq(period, "90.00", "财务-小王"));
        assertTrue(regenerated.getSettlementNo().endsWith("-0002"),
                "序号不复用: " + regenerated.getSettlementNo());
        SettlementDetail regenDetail = settlementService.detail(regenerated.getId());
        assertTrue(regenDetail.getItems().stream().anyMatch(i -> i.getOrderId().equals(order.getId())));
    }

    @Test
    void emptySettleableOrdersRejected() {
        // 其他用例都已结算完自己建的完工单，此刻没有可结算数据
        BusinessException empty = assertThrows(BusinessException.class, () ->
                settlementService.generate(generateReq(uniqPeriod(), "80.00", "财务-小王")));
        assertTrue(empty.getMessage().contains("没有可结算"));
    }

    // ---------- 准备数据 ----------

    private Machine createMachine() {
        MachineCreateRequest req = new MachineCreateRequest();
        req.setMachineCode(uniq("M-ST-"));
        req.setMachineName("结算测试机");
        req.setRequestNo(uniq("REQ-ST-M-"));
        return machineService.create(req);
    }

    private WorkOrder createOrder(Long machineId, String prefix) {
        WorkOrderCreateRequest req = new WorkOrderCreateRequest();
        req.setOrderNo(uniq(prefix));
        req.setMachineId(machineId);
        req.setOperationType("收割");
        req.setRequestNo(uniq("REQ-ST-W-"));
        return workOrderService.create(req);
    }

    private WorkOrder completeOrder(Long machineId, String prefix) {
        return completeOrder(machineId, prefix, "10.00");
    }

    private WorkOrder completeOrder(Long machineId, String prefix, String trackArea) {
        WorkOrder order = createOrder(machineId, prefix);
        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(new BigDecimal(trackArea));
        track.setRequestNo(uniq("REQ-ST-TA-"));
        workOrderService.reportTrackArea(order.getId(), track);
        StatusTransitRequest start = new StatusTransitRequest();
        start.setTargetStatus(WorkOrderStatus.IN_PROGRESS);
        start.setRequestNo(uniq("REQ-ST-TR-"));
        workOrderService.transit(order.getId(), start);
        StatusTransitRequest complete = new StatusTransitRequest();
        complete.setTargetStatus(WorkOrderStatus.COMPLETED);
        complete.setRequestNo(uniq("REQ-ST-TR-"));
        return workOrderService.transit(order.getId(), complete);
    }

    private SettlementGenerateRequest generateReq(String period, String unitPrice, String operator) {
        SettlementGenerateRequest req = new SettlementGenerateRequest();
        req.setPeriod(period);
        req.setUnitPrice(new BigDecimal(unitPrice));
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-ST-G-"));
        return req;
    }

    private SettlementTransitRequest transitReq(String target, int version, String operator, String remark) {
        SettlementTransitRequest req = new SettlementTransitRequest();
        req.setTargetStatus(target);
        req.setVersion(version);
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-ST-T-"));
        req.setRemark(remark);
        return req;
    }

    private TransitionToggleRequest toggleReq(boolean enabled, int version, String operator) {
        TransitionToggleRequest req = new TransitionToggleRequest();
        req.setEnabled(enabled);
        req.setVersion(version);
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-ST-R-"));
        return req;
    }

    private SettlementTransition findRule(String from, String to) {
        return settlementService.listTransitions().stream()
                .filter(r -> from.equals(r.getFromStatus()) && to.equals(r.getToStatus()))
                .findFirst().orElseThrow(IllegalStateException::new);
    }

    private void assertLog(SettlementDetail detail, int index, String from, String to,
                           int fromVersion, int toVersion, String operator) {
        SettlementStatusLog log = detail.getStatusLogs().get(index);
        assertEquals(from, log.getFromStatus());
        assertEquals(to, log.getToStatus());
        assertEquals(Integer.valueOf(fromVersion), log.getFromVersion());
        assertEquals(Integer.valueOf(toVersion), log.getToVersion());
        assertEquals(operator, log.getOperator());
    }

    private String uniq(String prefix) {
        return prefix + System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    /** 每期唯一且符合 YYYY-MM：年份随序号偏移。 */
    private String uniqPeriod() {
        int n = SEQ.incrementAndGet();
        return String.format("%04d-%02d", 2026 + n / 12, n % 12 + 1);
    }
}
