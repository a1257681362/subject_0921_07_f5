package com.evops.settlement.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 状态流转统一入口请求：目标状态 + 当前版本 + 操作人 + 请求号，四者缺一不可。
 * 是否放行由 t_settlement_transition 配置决定，非法迁移直接拒绝。
 */
@Data
public class SettlementTransitRequest {
    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    /** 作废（目标 CANCELLED）时必填作废原因。 */
    private String remark;
}
