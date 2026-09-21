package com.milk.order.common.enums;

import lombok.Getter;

/**
 * 退款单状态枚举（退款域父过程）
 *
 * 状态流转：待审核 → 已审核待退款 → 已退款；待审核 → 已拒绝（可重新申请）。
 * 全部迁移经 {@code ProcessTransitionExecutor}（场景 REFUND），规则白名单见 data.sql。
 */
@Getter
public enum RefundStatus {

    PENDING_AUDIT(1, "待审核"),
    AUDITED(2, "已审核待退款"),
    REFUNDED(3, "已退款"),
    REJECTED(4, "已拒绝"),
    CANCELLED(5, "已取消");

    private final Integer code;
    private final String desc;

    RefundStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 是否为进行中状态（待审核/已审核待退款）：与 refund_order.active_order_id 生成列的判定口径一致 */
    public static boolean isActive(Integer status) {
        return PENDING_AUDIT.getCode().equals(status) || AUDITED.getCode().equals(status);
    }
}
