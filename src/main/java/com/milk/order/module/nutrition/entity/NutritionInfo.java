package com.milk.order.module.nutrition.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 营养成分表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nutrition_info")
public class NutritionInfo extends BaseEntity {

    /** 奶品 ID */
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

    /** 备注 */
    private String remark;
}
