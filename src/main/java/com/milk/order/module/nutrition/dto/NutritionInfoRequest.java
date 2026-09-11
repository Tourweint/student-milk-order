package com.milk.order.module.nutrition.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 营养成分保存请求 DTO（按 productId 幂等：存在则更新）
 */
@Data
public class NutritionInfoRequest implements Serializable {

    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 能量（千焦/100ml） */
    private BigDecimal energy;

    /** 蛋白质（克/100ml） */
    private BigDecimal protein;

    /** 脂肪（克/100ml） */
    private BigDecimal fat;

    /** 碳水化合物（克/100ml） */
    private BigDecimal carbohydrate;

    /** 钙（毫克/100ml） */
    private BigDecimal calcium;

    /** 钠（毫克/100ml） */
    private BigDecimal sodium;

    private String remark;
}
