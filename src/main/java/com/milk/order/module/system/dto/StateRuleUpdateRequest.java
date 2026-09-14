package com.milk.order.module.system.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 修改状态迁移规则请求
 */
@Data
public class StateRuleUpdateRequest implements Serializable {

    /** 规则 ID */
    @NotNull(message = "规则ID不能为空")
    private Long id;

    /** 是否允许：1-允许，0-禁止（为空表示不修改） */
    private Integer allowed;

    /** 规则说明（为空表示不修改） */
    private String description;
}
