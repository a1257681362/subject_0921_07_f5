package com.evops.settlement;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 结算单状态。是否允许流转不由这里硬编码，而以 t_settlement_transition 配置为准；
 * 本类只负责状态字典与合法性判断。
 */
public final class SettlementStatus {
    /** 草稿：生成后待确认。 */
    public static final String DRAFT = "DRAFT";
    /** 已确认：核对无误，待审批。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 已审批：待付款。 */
    public static final String APPROVED = "APPROVED";
    /** 已付款：终态。 */
    public static final String PAID = "PAID";
    /** 已作废：终态，明细作业单释放可重新结算。 */
    public static final String CANCELLED = "CANCELLED";

    private static final Set<String> ALL = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(DRAFT, CONFIRMED, APPROVED, PAID, CANCELLED)));

    private SettlementStatus() {
    }

    public static boolean isKnown(String status) {
        return status != null && ALL.contains(status);
    }
}
