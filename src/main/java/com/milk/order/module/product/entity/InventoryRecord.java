package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库存变动记录表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inventory_record")
public class InventoryRecord extends BaseEntity {

    /** 奶品 ID */
    private Long productId;

    /** 变动类型：1-入库，2-出库，3-盘盈，4-盘亏 */
    private Integer changeType;

    /** 变动数量（正数增加，负数减少） */
    private Integer changeQuantity;

    /** 变动前库存 */
    private Integer beforeQuantity;

    /** 变动后库存 */
    private Integer afterQuantity;

    /** 关联订单 ID（出库时关联） */
    private Long orderId;

    /** 操作人 ID */
    private Long operatorId;

    /** 备注 */
    private String remark;
}
