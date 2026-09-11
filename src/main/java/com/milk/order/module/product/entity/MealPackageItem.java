package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 套餐明细表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_package_item")
public class MealPackageItem extends BaseEntity {

    /** 套餐 ID */
    private Long packageId;

    /** 奶品 ID */
    private Long productId;

    /** 数量（每日配送瓶数） */
    private Integer quantity;
}
