package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 批量开始配送（今日已送出）请求
 */
@Data
public class BatchStartRequest {

    /** 配送日期（yyyy-MM-dd，必填） */
    @NotBlank(message = "请选择配送日期")
    private String deliveryDate;

    /** 班级 ID（可选；不选则该日期全部班级） */
    private Long classId;
}
