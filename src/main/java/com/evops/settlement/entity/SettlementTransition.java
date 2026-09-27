package com.evops.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 结算状态机迁移规则（配置化）。from_status -> to_status 唯一；
 * enabled=0 即停用该迁移，运行期在线调整即时生效、重启不丢。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settlement_transition")
public class SettlementTransition extends BaseEntity {
    private String fromStatus;
    private String toStatus;
    /** 动作名（确认/审批通过/付款/作废/退回……），仅作展示。 */
    private String actionName;
    /** 1 启用 / 0 停用。 */
    private String enabled;
    private String remark;

    @Version
    private Integer version = 0;
}
