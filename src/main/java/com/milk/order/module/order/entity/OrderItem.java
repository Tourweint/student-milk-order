package com.milk.order.module.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订单明细表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
public class OrderItem extends BaseEntity {

    /** 订单 ID */
    private Long orderId;

    /** 奶品 ID */
    private Long productId;

    /** 奶品名称（快照） */
    private String productName;

    /** 规格（快照） */
    private String spec;

    /** 单价（元，快照） */
    private BigDecimal price;

    /** 数量（每日瓶数） */
    private Integer quantity;

    /** 小计金额（元） */
    private BigDecimal subtotal;
}
