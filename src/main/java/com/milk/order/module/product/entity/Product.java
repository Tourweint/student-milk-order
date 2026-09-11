package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 奶品表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class Product extends BaseEntity {

    /** 奶品名称 */
    private String productName;

    /** 品类 ID */
    private Long categoryId;

    /** 规格（如：200ml/盒、250ml/瓶） */
    private String spec;

    /** 口味（原味、草莓、巧克力等） */
    private String flavor;

    /** 单价（元） */
    private BigDecimal price;

    /** 成本价（元） */
    private BigDecimal costPrice;

    /** 图片 URL */
    private String image;

    /** 描述 */
    private String description;

    /** 状态：0-下架，1-上架 */
    private Integer status;

    /** 排序 */
    private Integer sort;

    /** 营养成分 ID（关联 nutrition_info） */
    private Long nutritionId;
}
