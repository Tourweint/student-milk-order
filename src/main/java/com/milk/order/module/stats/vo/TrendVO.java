package com.milk.order.module.stats.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 订单趋势 VO
 */
@Data
public class TrendVO implements Serializable {

    /** 日期/月份标签 */
    private List<String> dates;

    /** 订单数 */
    private List<Long> orderCounts;

    /** 销售额 */
    private List<BigDecimal> sales;
}
