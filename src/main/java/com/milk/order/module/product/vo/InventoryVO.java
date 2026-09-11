package com.milk.order.module.product.vo;

import com.milk.order.module.product.entity.Inventory;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存视图对象（附带奶品名称、规格、品类名称、是否预警）
 */
@Data
public class InventoryVO implements Serializable {

    private Long id;

    private Long productId;

    /** 奶品名称 */
    private String productName;

    /** 规格 */
    private String spec;

    /** 品类名称 */
    private String categoryName;

    /** 当前库存数量 */
    private Integer quantity;

    /** 预警阈值 */
    private Integer warningThreshold;

    /** 是否低于预警阈值 */
    private Boolean warning;

    private String warehouseLocation;

    private String remark;

    private LocalDateTime createTime;

    public static InventoryVO from(Inventory inv, String productName, String spec, String categoryName) {
        InventoryVO vo = new InventoryVO();
        vo.setId(inv.getId());
        vo.setProductId(inv.getProductId());
        vo.setProductName(productName);
        vo.setSpec(spec);
        vo.setCategoryName(categoryName);
        vo.setQuantity(inv.getQuantity());
        vo.setWarningThreshold(inv.getWarningThreshold());
        int threshold = inv.getWarningThreshold() == null ? 0 : inv.getWarningThreshold();
        int qty = inv.getQuantity() == null ? 0 : inv.getQuantity();
        vo.setWarning(qty <= threshold);
        vo.setWarehouseLocation(inv.getWarehouseLocation());
        vo.setRemark(inv.getRemark());
        vo.setCreateTime(inv.getCreateTime());
        return vo;
    }
}
