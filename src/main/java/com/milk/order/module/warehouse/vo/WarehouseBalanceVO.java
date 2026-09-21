package com.milk.order.module.warehouse.vo;

import lombok.Data;

/**
 * 仓库余量（按品种）。
 *
 * <p>同时充当聚合查询的投影载体：{@code productId} / {@code balance} 由
 * {@code WarehouseLedgerMapper.sumBalanceGroupByProduct} 直接映射（列别名即属性名），
 * {@code productName} 由 Service 补齐。</p>
 */
@Data
public class WarehouseBalanceVO {

    /** 奶品 ID */
    private Long productId;

    /** 奶品名称 */
    private String productName;

    /** 当前仓库余量 W（盒） */
    private Integer balance;
}
