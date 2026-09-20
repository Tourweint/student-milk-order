package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 配送例外新增/修改请求（仅管理员）。
 */
@Data
public class DeliveryExceptionSaveRequest implements Serializable {

    /** 例外ID；为空表示新增，非空表示修改 */
    private Long id;

    /** 例外日期（yyyy-MM-dd，须不早于今天） */
    @NotBlank(message = "例外日期不能为空")
    private String exceptionDate;

    /** 类型：1-停送，2-补送（补课） */
    @NotNull(message = "例外类型不能为空")
    private Integer type;

    /** 备注 */
    private String remark;
}
