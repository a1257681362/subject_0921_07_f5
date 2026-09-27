package com.evops.settlement.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.math.BigDecimal;

/** 生成结算单：把已完工未结算的作业单按期间一次汇总成单。 */
@Data
public class SettlementGenerateRequest {
    @NotBlank(message = "结算期间不能为空")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "结算期间格式应为 YYYY-MM")
    private String period;
    @NotNull(message = "每亩单价不能为空")
    private BigDecimal unitPrice;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
