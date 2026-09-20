package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 配送例外删除请求（仅管理员）。
 */
@Data
public class DeliveryExceptionDeleteRequest implements Serializable {

    /** 例外ID */
    @NotNull(message = "例外ID不能为空")
    private Long id;
}
