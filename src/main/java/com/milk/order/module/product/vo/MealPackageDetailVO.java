package com.milk.order.module.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 套餐详情视图对象（含固定配送明细）
 */
@Data
public class MealPackageDetailVO implements Serializable {

    private Long id;

    private String packageName;

    /** 套餐类型：1-按月套餐，2-按学期套餐 */
    private Integer packageType;

    private String description;

    private BigDecimal originalPrice;

    private BigDecimal discountPrice;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 状态：0-下架，1-上架 */
    private Integer status;

    private Integer sort;

    /** 套餐固定配送明细（家长不可自选） */
    private List<PackageItemVO> items;

    @Data
    public static class PackageItemVO implements Serializable {

        private Long productId;

        private String productName;

        private String spec;

        /** 每日配送盒数 */
        private Integer quantity;
    }
}
