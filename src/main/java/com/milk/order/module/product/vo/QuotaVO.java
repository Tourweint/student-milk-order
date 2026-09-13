package com.milk.order.module.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 每日机动配额视图对象（按品种）
 */
@Data
public class QuotaVO implements Serializable {

    private Long id;

    private LocalDate quotaDate;

    private Long productId;

    private String productName;

    /** 当日该品种机动总盒数 */
    private Integer totalQuota;

    /** 已售盒数 */
    private Integer usedQuota;

    /** 剩余盒数（含保质期内结转） */
    private Integer remaining;
}
