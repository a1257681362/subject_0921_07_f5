package com.evops.settlement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.request.RequestRecordService;
import com.evops.settlement.dto.SettlementGenerateRequest;
import com.evops.settlement.dto.SettlementTransitRequest;
import com.evops.settlement.dto.TransitionToggleRequest;
import com.evops.settlement.entity.Settlement;
import com.evops.settlement.entity.SettlementItem;
import com.evops.settlement.entity.SettlementStatusLog;
import com.evops.settlement.entity.SettlementTransition;
import com.evops.settlement.mapper.SettlementItemMapper;
import com.evops.settlement.mapper.SettlementMapper;
import com.evops.settlement.mapper.SettlementStatusLogMapper;
import com.evops.settlement.mapper.SettlementTransitionMapper;
import com.evops.settlement.vo.SettlementDetail;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 结算单服务。所有状态变更只走 {@link #transit} 一个入口：
 * 请求号登记 → 版本校验 → 状态机规则校验（配置化）→ 落库 + 流转日志，同一事务。
 */
@Service
public class SettlementService {

    private static final String TAG_ACTIVE = "ACTIVE";

    private final SettlementMapper settlementMapper;
    private final SettlementItemMapper itemMapper;
    private final SettlementStatusLogMapper logMapper;
    private final SettlementTransitionMapper transitionMapper;
    private final WorkOrderMapper workOrderMapper;
    private final RequestRecordService requestRecordService;
    private final SettlementStateMachine stateMachine;

    public SettlementService(SettlementMapper settlementMapper,
                             SettlementItemMapper itemMapper,
                             SettlementStatusLogMapper logMapper,
                             SettlementTransitionMapper transitionMapper,
                             WorkOrderMapper workOrderMapper,
                             RequestRecordService requestRecordService,
                             SettlementStateMachine stateMachine) {
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.logMapper = logMapper;
        this.transitionMapper = transitionMapper;
        this.workOrderMapper = workOrderMapper;
        this.requestRecordService = requestRecordService;
        this.stateMachine = stateMachine;
    }

    /**
     * 生成结算单：把已完工、未红冲、未进入有效结算单的作业单一次汇总成单，
     * 明细快照面积与金额，结算依据直接落库，不再导出二次加工。
     * 同一期间只允许一张有效结算单（uk_settlement_period_active 兜底并发）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Settlement generate(SettlementGenerateRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SETTLEMENT_GENERATE, req.getPeriod());

        if (req.getUnitPrice().signum() <= 0) {
            throw new BusinessException("每亩单价必须大于 0");
        }
        Settlement existing = findActiveByPeriod(req.getPeriod());
        if (existing != null) {
            throw new BusinessException("期间 " + req.getPeriod() + " 已存在进行中的结算单 "
                    + existing.getSettlementNo() + "（状态 " + existing.getStatus() + "），不能重复生成");
        }

        List<WorkOrder> orders = listSettleableOrders();
        if (orders.isEmpty()) {
            throw new BusinessException("没有可结算的完工作业单（需已完工、未红冲且未进入其他结算单）");
        }

        Settlement settlement = new Settlement();
        settlement.setSettlementNo(nextSettlementNo(req.getPeriod()));
        settlement.setPeriod(req.getPeriod());
        settlement.setStatus(SettlementStatus.DRAFT);
        settlement.setUnitPrice(req.getUnitPrice());
        settlement.setActiveTag(TAG_ACTIVE);
        settlement.setRemark(req.getRemark());

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WorkOrder order : orders) {
            totalArea = totalArea.add(order.getTrackArea());
            totalAmount = totalAmount.add(amountOf(order.getTrackArea(), req.getUnitPrice()));
        }
        settlement.setOrderCount(orders.size());
        settlement.setTotalArea(totalArea);
        settlement.setTotalAmount(totalAmount);
        try {
            settlementMapper.insert(settlement);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("期间 " + req.getPeriod() + " 已存在进行中的结算单，不能重复生成");
        }

        for (WorkOrder order : orders) {
            SettlementItem item = new SettlementItem();
            item.setSettlementId(settlement.getId());
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            item.setMachineId(order.getMachineId());
            item.setOperationType(order.getOperationType());
            item.setTrackArea(order.getTrackArea());
            item.setUnitPrice(req.getUnitPrice());
            item.setAmount(amountOf(order.getTrackArea(), req.getUnitPrice()));
            item.setActiveTag(TAG_ACTIVE);
            itemMapper.insert(item);
        }

        writeLog(settlement, null, SettlementStatus.DRAFT, null, 0, req.getOperator(),
                req.getRequestNo(), "结算单生成（" + orders.size() + " 张作业单，合计 "
                        + totalArea.toPlainString() + " 亩）");
        return settlement;
    }

    /**
     * 状态流转统一入口。所有变更必须携带当前版本与操作人：
     * 版本不一致说明已被他人变更，直接拒绝；目标状态经状态机配置校验，非法迁移直接拒绝。
     * 状态、版本、流转日志同一事务提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public Settlement transit(Long id, SettlementTransitRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SETTLEMENT_TRANSIT, String.valueOf(id));

        Settlement settlement = requireSettlement(id);
        if (!req.getVersion().equals(settlement.getVersion())) {
            throw new BusinessException("结算单已被他人变更（当前版本 " + settlement.getVersion()
                    + "，请求版本 " + req.getVersion() + "），请刷新后重试");
        }
        String from = settlement.getStatus();
        String to = req.getTargetStatus();
        SettlementTransition rule = stateMachine.requireTransitable(from, to);
        if (SettlementStatus.CANCELLED.equals(to)
                && (req.getRemark() == null || req.getRemark().trim().isEmpty())) {
            throw new BusinessException("作废必须填写作废原因（remark）");
        }

        Integer fromVersion = settlement.getVersion();
        settlement.setStatus(to);
        if (SettlementStatus.CANCELLED.equals(to)) {
            // 作废即释放：期间占位与明细作业单占用一并解除，历史记录保留不删。
            settlement.setActiveTag(releasedTag(id));
        }
        if (settlementMapper.updateById(settlement) == 0) {
            throw new BusinessException("结算单已被他人变更，请刷新后重试");
        }
        if (SettlementStatus.CANCELLED.equals(to)) {
            itemMapper.update(null, new LambdaUpdateWrapper<SettlementItem>()
                    .eq(SettlementItem::getSettlementId, id)
                    .eq(SettlementItem::getActiveTag, TAG_ACTIVE)
                    .set(SettlementItem::getActiveTag, releasedTag(id)));
        }

        writeLog(settlement, from, to, fromVersion, fromVersion + 1, req.getOperator(),
                req.getRequestNo(),
                req.getRemark() != null && !req.getRemark().trim().isEmpty()
                        ? req.getRemark() : rule.getActionName());
        return settlement;
    }

    /** 迁移规则启停（配置化）：带规则版本，变更走请求号台账，即时生效。 */
    @Transactional(rollbackFor = Exception.class)
    public SettlementTransition toggleTransition(Long id, TransitionToggleRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SETTLEMENT_RULE_UPDATE,
                "transition:" + id + "->enabled=" + req.getEnabled() + " by " + req.getOperator());

        SettlementTransition rule = transitionMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException("迁移规则不存在: " + id);
        }
        if (!req.getVersion().equals(rule.getVersion())) {
            throw new BusinessException("迁移规则已被他人变更（当前版本 " + rule.getVersion() + "），请刷新后重试");
        }
        rule.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? "1" : "0");
        if (transitionMapper.updateById(rule) == 0) {
            throw new BusinessException("迁移规则已被他人变更，请刷新后重试");
        }
        return rule;
    }

    /** 结算单详情：单头 + 明细快照 + 完整流转历史。 */
    public SettlementDetail detail(Long id) {
        Settlement settlement = requireSettlement(id);
        SettlementDetail detail = new SettlementDetail();
        detail.setSettlement(settlement);
        detail.setItems(itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                .eq(SettlementItem::getSettlementId, id)
                .orderByAsc(SettlementItem::getId)));
        detail.setStatusLogs(logMapper.selectList(new LambdaQueryWrapper<SettlementStatusLog>()
                .eq(SettlementStatusLog::getSettlementId, id)
                .orderByAsc(SettlementStatusLog::getId)));
        return detail;
    }

    /** 列表：可按状态/期间过滤，最新在前。 */
    public List<Settlement> list(String status, String period, int limit) {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<Settlement>()
                .eq(status != null && !status.isEmpty(), Settlement::getStatus, status)
                .eq(period != null && !period.isEmpty(), Settlement::getPeriod, period)
                .orderByDesc(Settlement::getId)
                .last("limit " + Math.max(1, Math.min(limit, 100)));
        return settlementMapper.selectList(wrapper);
    }

    /** 当前迁移规则配置（状态机全集）。 */
    public List<SettlementTransition> listTransitions() {
        return stateMachine.listRules();
    }

    /** 已完工、未红冲、有权威轨迹面积、且未进入任何有效结算单的作业单。 */
    private List<WorkOrder> listSettleableOrders() {
        List<Long> occupiedOrderIds = itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                        .eq(SettlementItem::getActiveTag, TAG_ACTIVE))
                .stream().map(SettlementItem::getOrderId).collect(Collectors.toList());
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getStatus, WorkOrderStatus.COMPLETED)
                .eq(WorkOrder::getReversalFlag, "0")
                .isNotNull(WorkOrder::getTrackArea)
                .notIn(!occupiedOrderIds.isEmpty(), WorkOrder::getId, occupiedOrderIds)
                .orderByAsc(WorkOrder::getId);
        return workOrderMapper.selectList(wrapper);
    }

    private Settlement findActiveByPeriod(String period) {
        return settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getPeriod, period)
                .eq(Settlement::getActiveTag, TAG_ACTIVE)
                .last("limit 1"));
    }

    private Settlement requireSettlement(Long id) {
        Settlement settlement = settlementMapper.selectById(id);
        if (settlement == null) {
            throw new BusinessException("结算单不存在: " + id);
        }
        return settlement;
    }

    /** 结算单号：ST + 期间 + 序号，序号按期间内已生成（含作废）张数递增，不复用。 */
    private String nextSettlementNo(String period) {
        long count = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getPeriod, period));
        return "ST" + period.replace("-", "") + "-" + String.format("%04d", count + 1);
    }

    private BigDecimal amountOf(BigDecimal trackArea, BigDecimal unitPrice) {
        return trackArea.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private String releasedTag(Long settlementId) {
        return "CANCELLED-" + settlementId;
    }

    private void writeLog(Settlement settlement, String fromStatus, String toStatus,
                          Integer fromVersion, Integer toVersion, String operator,
                          String requestNo, String remark) {
        SettlementStatusLog log = new SettlementStatusLog();
        log.setSettlementId(settlement.getId());
        log.setSettlementNo(settlement.getSettlementNo());
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setFromVersion(fromVersion);
        log.setToVersion(toVersion);
        log.setOperator(operator);
        log.setRequestNo(requestNo);
        log.setRemark(remark);
        logMapper.insert(log);
    }
}
