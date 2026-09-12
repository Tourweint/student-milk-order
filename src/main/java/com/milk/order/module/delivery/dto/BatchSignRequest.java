package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 批量签收请求
 */
@Data
public class BatchSignRequest {

    /** 配送日期（yyyy-MM-dd，必填） */
    @NotBlank(message = "请选择配送日期")
    private String deliveryDate;

    /** 班级 ID（可选；班主任操作时后端强制限定为本班） */
    private Long classId;
}
