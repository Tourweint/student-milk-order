package com.milk.order.module.refund.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款单展示对象 */
@Data
public class RefundOrderVO {

    private Long id;

    private String refundNo;

    private Long orderId;

    private String orderNo;

    private Long studentId;

    private String studentName;

    private Long userId;

    private Integer applyBoxCount;

    private Integer refundedBoxes;

    private BigDecimal refundAmount;

    private Integer status;

    private String statusText;

    private String applyReason;

    private String auditRemark;

    private LocalDateTime auditTime;

    private LocalDateTime refundTime;

    private LocalDateTime createTime;
}
