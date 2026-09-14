package com.milk.order.module.system.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 修改系统参数请求
 */
@Data
public class SysConfigUpdateRequest implements Serializable {

    /** 配置 ID */
    @NotNull(message = "配置ID不能为空")
    private Long id;

    /** 配置值 */
    @NotBlank(message = "配置值不能为空")
    private String configValue;
}
