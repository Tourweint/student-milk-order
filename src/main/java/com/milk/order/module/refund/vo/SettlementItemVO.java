package com.milk.order.module.refund.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 毕业清算逐单处理结果（单笔失败不阻塞其余，失败原因回填此处供人工跟进） */
@Data
public class SettlementItemVO {

    private Long orderId;

    private String orderNo;

    private Integer orderStatus;

    /** CANCELLED_UNPAID-待支付单已取消；REFUNDED-已退款；SKIPPED-无需处理；FAILED-处理失败 */
    private String action;

    private String refundNo;

    private Integer refundedBoxes;

    private BigDecimal refundAmount;

    private String message;
}
