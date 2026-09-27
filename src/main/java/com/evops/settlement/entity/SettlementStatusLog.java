package com.evops.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 结算单状态流转日志：每次变更记录前后状态、前后版本与操作人。
 * 只追加、不修改，流转历史完整可查。
 */
@Data
@TableName("t_settlement_status_log")
public class SettlementStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long settlementId;
    private String settlementNo;
    private String fromStatus;
    private String toStatus;
    /** 变更前版本（建档时为 null）。 */
    private Integer fromVersion;
    /** 变更后版本，与结算单当前 version 一致。 */
    private Integer toVersion;
    /** 操作人（请求方提供，原样留痕）。 */
    private String operator;
    private String requestNo;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
