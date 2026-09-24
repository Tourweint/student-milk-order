package com.milk.order.module.product.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 套餐保存请求（含固定配送明细，明细即套餐每日配送内容，家长不可自选）
 */
@Data
public class PackageSaveRequest {

    private Long id;

    @NotBlank(message = "套餐名称不能为空")
    private String packageName;

    /**
     * 套餐类型：仅保留 {@code 2-按学期套餐}（全校统一预约定制）。
     *
     * <p>月度套餐已下线，服务端在保存时**强制置为 2**，前端不再提供类型选择；
     * 本字段为兼容旧数据与请求体而保留。</p>
     */
    private Integer packageType;

    private String description;

    private BigDecimal originalPrice;

    private BigDecimal discountPrice;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer status;

    private Integer sort;

    /** 套餐固定配送明细 */
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "奶品不能为空")
        private Long productId;

        /** 每日配送盒数 */
        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "每日配送盒数至少为1")
        private Integer quantity;
    }
}
