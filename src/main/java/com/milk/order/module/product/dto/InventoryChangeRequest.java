package com.milk.order.module.product.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 库存变动请求 DTO（入库/出库/盘盈/盘亏）
 */
@Data
public class InventoryChangeRequest implements Serializable {

    /** 奶品 ID */
    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 变动类型：1-入库，2-出库，3-盘盈，4-盘亏 */
    @NotNull(message = "变动类型不能为空")
    @Min(value = 1, message = "变动类型非法")
    @Max(value = 4, message = "变动类型非法")
    private Integer changeType;

    /** 变动数量（正整数，方向由 changeType 决定） */
    @NotNull(message = "变动数量不能为空")
    @Min(value = 1, message = "变动数量必须大于 0")
    private Integer changeQuantity;

    /** 备注 */
    private String remark;
}
