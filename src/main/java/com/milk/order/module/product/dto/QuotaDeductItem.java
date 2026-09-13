package com.milk.order.module.product.dto;

import lombok.Data;

/**
 * 配额扣减项（品种 + 盒数）
 */
@Data
public class QuotaDeductItem {

    private Long productId;

    private int boxes;

    public QuotaDeductItem() {
    }

    public QuotaDeductItem(Long productId, int boxes) {
        this.productId = productId;
        this.boxes = boxes;
    }
}
