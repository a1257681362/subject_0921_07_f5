package com.evops.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 结算单头。status 只允许走统一流转入口变更（规则见 t_settlement_transition）；
 * version 乐观锁，每次变更请求必须携带当前版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settlement")
public class Settlement extends BaseEntity {
    private String settlementNo;
    /** 结算期间，格式 YYYY-MM。 */
    private String period;
    private String status;
    private Integer orderCount;
    private BigDecimal totalArea;
    /** 每亩单价（元），生成时确定，明细同价。 */
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    /** 有效占位标记：ACTIVE 进行中；作废后为 CANCELLED-<id>，释放期间占位。 */
    private String activeTag;
    private String remark;

    @Version
    private Integer version = 0;
}
