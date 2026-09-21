package com.milk.order.module.refund.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 毕业清算结果（幂等：重复执行只会得到"无可清算订单"） */
@Data
public class SettlementResultVO {

    private Long studentId;

    /** 待清算订单数（待支付/已支付/配送中） */
    private Integer totalOrders;

    /** 已取消的待支付订单数 */
    private Integer cancelledCount;

    /** 已退款的订单数 */
    private Integer refundedCount;

    /** 无需处理（无可退期次）的订单数 */
    private Integer skippedCount;

    /** 处理失败的订单数 */
    private Integer failedCount;

    /** 本次退款金额合计（元） */
    private BigDecimal totalRefundAmount;

    /** 逐单处理明细 */
    private List<SettlementItemVO> items;
}
