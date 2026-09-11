package com.milk.order.module.order.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 订单明细项请求 DTO
 */
@Data
public class OrderItemRequest implements Serializable {

    /** 奶品 ID */
    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 数量（每日瓶数） */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer quantity;
}
