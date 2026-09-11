package com.milk.order.module.stats.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 班级订购排行 VO
 */
@Data
public class ClassRankingVO implements Serializable {

    private Long classId;
    private String className;
    /** 订单数 */
    private Long orderCount;
    /** 销售额 */
    private BigDecimal sales;
}
