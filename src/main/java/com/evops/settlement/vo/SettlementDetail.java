package com.evops.settlement.vo;

import com.evops.settlement.entity.Settlement;
import com.evops.settlement.entity.SettlementItem;
import com.evops.settlement.entity.SettlementStatusLog;
import lombok.Data;

import java.util.List;

/** 结算单详情：单头 + 明细快照 + 完整流转历史。 */
@Data
public class SettlementDetail {
    private Settlement settlement;
    private List<SettlementItem> items;
    private List<SettlementStatusLog> statusLogs;
}
