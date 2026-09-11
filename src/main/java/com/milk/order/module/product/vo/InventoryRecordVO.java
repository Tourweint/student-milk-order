package com.milk.order.module.product.vo;

import com.milk.order.module.product.entity.InventoryRecord;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存变动流水视图对象（附带奶品名称、操作人姓名）
 */
@Data
public class InventoryRecordVO implements Serializable {

    private Long id;

    private Long productId;

    /** 奶品名称 */
    private String productName;

    /** 变动类型：1-入库，2-出库，3-盘盈，4-盘亏 */
    private Integer changeType;

    /** 变动数量（带方向：入库/盘盈为正，出库/盘亏为负） */
    private Integer changeQuantity;

    private Integer beforeQuantity;

    private Integer afterQuantity;

    private Long orderId;

    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;

    private String remark;

    private LocalDateTime createTime;

    public static InventoryRecordVO from(InventoryRecord r, String productName, String operatorName) {
        InventoryRecordVO vo = new InventoryRecordVO();
        vo.setId(r.getId());
        vo.setProductId(r.getProductId());
        vo.setProductName(productName);
        vo.setChangeType(r.getChangeType());
        vo.setChangeQuantity(r.getChangeQuantity());
        vo.setBeforeQuantity(r.getBeforeQuantity());
        vo.setAfterQuantity(r.getAfterQuantity());
        vo.setOrderId(r.getOrderId());
        vo.setOperatorId(r.getOperatorId());
        vo.setOperatorName(operatorName);
        vo.setRemark(r.getRemark());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }
}
