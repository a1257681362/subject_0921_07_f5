package com.evops.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算单明细：生成时快照作业单，金额 = 轨迹核算面积 × 单价。
 * 快照即结算依据，结算不再依赖导出后二次加工。
 */
@Data
@TableName("t_settlement_item")
public class SettlementItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long settlementId;
    private Long orderId;
    private String orderNo;
    private Long machineId;
    private String operationType;
    /** 快照自作业单的权威轨迹核算面积。 */
    private BigDecimal trackArea;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    /** 有效占位标记：ACTIVE 占用该作业单；作废后为 CANCELLED-<结算单ID> 释放。 */
    private String activeTag;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
