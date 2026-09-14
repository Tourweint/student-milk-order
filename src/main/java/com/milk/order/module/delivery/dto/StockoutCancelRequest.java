package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 配送前缺货批量取消请求
 */
@Data
public class StockoutCancelRequest implements Serializable {

    /** 配送日期（yyyy-MM-dd） */
    @NotBlank(message = "配送日期不能为空")
    private String deliveryDate;

    /** 奶品 ID */
    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 取消原因（选填） */
    private String reason;
}
