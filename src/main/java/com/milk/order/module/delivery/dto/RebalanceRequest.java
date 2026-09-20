package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 学期末摊平请求
 */
@Data
public class RebalanceRequest implements Serializable {

    /** 订单 ID */
    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 截止日期（yyyy-MM-dd，须不早于今天） */
    @NotBlank(message = "截止日期不能为空")
    private String deadline;
}
