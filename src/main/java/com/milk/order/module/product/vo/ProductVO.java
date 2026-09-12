package com.milk.order.module.product.vo;

import com.milk.order.module.product.entity.Product;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 奶品视图对象（附带品类名称、当前库存）
 */
@Data
public class ProductVO implements Serializable {

    private Long id;

    private String productName;

    private Long categoryId;

    /** 品类名称 */
    private String categoryName;

    private String spec;

    private String flavor;

    private BigDecimal price;

    private BigDecimal costPrice;

    private String image;

    private String description;

    /** 状态：0-下架，1-上架 */
    private Integer status;

    private Integer sort;

    private Long nutritionId;

    private LocalDateTime createTime;

    public static ProductVO from(Product p, String categoryName) {
        ProductVO vo = new ProductVO();
        vo.setId(p.getId());
        vo.setProductName(p.getProductName());
        vo.setCategoryId(p.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setSpec(p.getSpec());
        vo.setFlavor(p.getFlavor());
        vo.setPrice(p.getPrice());
        vo.setCostPrice(p.getCostPrice());
        vo.setImage(p.getImage());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setSort(p.getSort());
        vo.setNutritionId(p.getNutritionId());
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }
}
