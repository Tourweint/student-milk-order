package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 奶品品类表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product_category")
public class ProductCategory extends BaseEntity {

    /** 品类名称（纯牛奶、酸奶、羊奶等） */
    private String categoryName;

    /** 品类编码 */
    private String categoryCode;

    /** 排序 */
    private Integer sort;

    /** 状态：0-下架，1-上架 */
    private Integer status;

    /** 备注 */
    private String remark;
}
