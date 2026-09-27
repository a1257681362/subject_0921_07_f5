package com.evops.settlement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.settlement.entity.SettlementTransition;
import com.evops.settlement.mapper.SettlementTransitionMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 结算单状态机：迁移规则全部来自 t_settlement_transition 配置表，不做硬编码。
 * 每次流转实时读配置，规则启停即时生效；非法迁移在此统一拒绝。
 */
@Component
public class SettlementStateMachine {

    private final SettlementTransitionMapper transitionMapper;

    public SettlementStateMachine(SettlementTransitionMapper transitionMapper) {
        this.transitionMapper = transitionMapper;
    }

    /**
     * 校验 from -> to 是否为当前启用的合法迁移。
     * 目标状态不存在、规则未配置或已停用，都视为非法迁移，拒绝并给出当前可去的方向。
     */
    public SettlementTransition requireTransitable(String from, String to) {
        if (!SettlementStatus.isKnown(to)) {
            throw new BusinessException("未知目标状态: " + to);
        }
        SettlementTransition rule = findRule(from, to);
        if (rule == null) {
            throw new BusinessException("结算单状态不允许从 " + from + " 流转到 " + to
                    + "；当前可流转方向: " + allowedTargets(from));
        }
        if (!"1".equals(rule.getEnabled())) {
            throw new BusinessException("流转 " + from + " -> " + to + "（" + rule.getActionName()
                    + "）已被配置停用，请联系管理员启用后再操作");
        }
        return rule;
    }

    /** 当前状态下按配置（含停用标记）可去的方向说明，用于报错提示与前端展示。 */
    public String allowedTargets(String from) {
        List<SettlementTransition> rules = transitionMapper.selectList(
                new LambdaQueryWrapper<SettlementTransition>()
                        .eq(SettlementTransition::getFromStatus, from)
                        .orderByAsc(SettlementTransition::getId));
        if (rules.isEmpty()) {
            return "无（终态）";
        }
        return rules.stream()
                .map(r -> r.getToStatus() + "(" + r.getActionName()
                        + ("1".equals(r.getEnabled()) ? "" : ",已停用") + ")")
                .collect(Collectors.joining("、"));
    }

    public List<SettlementTransition> listRules() {
        return transitionMapper.selectList(
                new LambdaQueryWrapper<SettlementTransition>().orderByAsc(SettlementTransition::getId));
    }

    private SettlementTransition findRule(String from, String to) {
        return transitionMapper.selectOne(new LambdaQueryWrapper<SettlementTransition>()
                .eq(SettlementTransition::getFromStatus, from)
                .eq(SettlementTransition::getToStatus, to));
    }
}
