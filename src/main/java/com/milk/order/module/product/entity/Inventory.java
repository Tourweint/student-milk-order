package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库存表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inventory")
public class Inventory extends BaseEntity {

    /** 奶品 ID */
    private Long productId;

    /** 当前库存数量 */
    private Integer quantity;

    /** 预警阈值（低于此值触发预警） */
    private Integer warningThreshold;

    /** 仓库位置 */
    private String warehouseLocation;

    /** 备注 */
    private String remark;
}
