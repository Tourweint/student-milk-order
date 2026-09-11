package com.milk.order.module.product.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 库存阈值/库位更新请求 DTO
 */
@Data
public class InventoryThresholdRequest implements Serializable {

    @NotNull(message = "库存记录ID不能为空")
    private Long id;

    /** 预警阈值 */
    private Integer warningThreshold;

    /** 仓库位置 */
    private String warehouseLocation;
}
