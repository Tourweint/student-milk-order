package com.milk.order.module.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_record")
public class PaymentRecord extends BaseEntity {

    /** 订单 ID */
    private Long orderId;

    /** 订单编号 */
    private String orderNo;

    /** 支付流水号 */
    private String transactionId;

    /** 支付金额（元） */
    private BigDecimal amount;

    /** 支付方式：1-模拟支付 */
    private Integer payType;

    /** 支付状态：1-待支付，2-支付成功，3-支付失败 */
    private Integer status;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 支付用户 ID */
    private Long userId;

    /** 备注 */
    private String remark;
}
