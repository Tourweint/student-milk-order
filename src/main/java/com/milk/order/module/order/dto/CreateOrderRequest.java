package com.milk.order.module.order.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建订单请求 DTO
 */
@Data
public class CreateOrderRequest implements Serializable {

    /** 学生 ID */
    @NotNull(message = "学生不能为空")
    private Long studentId;

    /** 套餐 ID（可选，用于记录订购套餐；金额优先取套餐优惠价） */
    private Long packageId;

    /** 配送开始日期 */
    @NotNull(message = "配送开始日期不能为空")
    private LocalDate deliveryStartDate;

    /** 配送结束日期 */
    @NotNull(message = "配送结束日期不能为空")
    private LocalDate deliveryEndDate;

    /**
     * 订单明细（奶品 + 数量）。
     * 散订/购物车必传（数量=订购盒数）；套餐订单可缺省，服务端以套餐固定配置为准。
     */
    @Valid
    private List<OrderItemRequest> items;

    /** 备注 */
    private String remark;
}
