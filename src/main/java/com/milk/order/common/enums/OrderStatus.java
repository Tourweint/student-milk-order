package com.milk.order.common.enums;

import lombok.Getter;

/**
 * 订单状态枚举
 * 状态流转：待支付 → 已支付 → 配送中 → 已完成 / 已退订
 */
@Getter
public enum OrderStatus {

    PENDING_PAYMENT(1, "待支付"),
    PAID(2, "已支付"),
    DELIVERING(3, "配送中"),
    COMPLETED(4, "已完成"),
    CANCELLED(5, "已退订");

    private final Integer code;
    private final String desc;

    OrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
