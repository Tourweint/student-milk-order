package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 套餐表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_package")
public class MealPackage extends BaseEntity {

    /** 套餐名称 */
    private String packageName;

    /** 套餐类型：1-按月套餐，2-按学期套餐 */
    private Integer packageType;

    /** 套餐描述 */
    private String description;

    /** 套餐原价（元） */
    private BigDecimal originalPrice;

    /** 套餐优惠价（元） */
    private BigDecimal discountPrice;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期 */
    private LocalDate endDate;

    /** 状态：0-下架，1-上架 */
    private Integer status;

    /** 排序 */
    private Integer sort;
}
