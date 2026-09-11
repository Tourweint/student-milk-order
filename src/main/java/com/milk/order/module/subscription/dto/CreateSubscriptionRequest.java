package com.milk.order.module.subscription.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 创建续订计划请求 DTO
 */
@Data
public class CreateSubscriptionRequest implements Serializable {

    /** 学生 ID */
    @NotNull(message = "学生不能为空")
    private Long studentId;

    /** 套餐 ID */
    @NotNull(message = "套餐不能为空")
    private Long packageId;

    /** 原订单 ID（基于哪个订单续订） */
    @NotNull(message = "原订单不能为空")
    private Long originalOrderId;

    /** 续订周期：1-每月续订 */
    private Integer cycleType = 1;

    /** 备注 */
    private String remark;
}
