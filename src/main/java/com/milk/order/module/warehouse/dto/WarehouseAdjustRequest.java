package com.milk.order.module.warehouse.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 仓库台账修正请求（仅管理员）。
 *
 * <p>台账只增不改（R7）：数字记错不能改历史行，只能记一条反向 {@code ADJ} 冲销，
 * 因此 {@code quantity} **带符号**（正=增加余量，负=减少余量）且必须写原因。</p>
 */
@Data
public class WarehouseAdjustRequest {

    /** 奶品 ID */
    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 修正盒数：正=增加余量，负=减少余量；不得为 0 */
    @NotNull(message = "请填写修正盒数")
    private Integer quantity;

    /** 修正原因（必填：这条账要能被人看懂，否则对账时无从判断） */
    @NotBlank(message = "请填写修正原因")
    private String reason;
}
