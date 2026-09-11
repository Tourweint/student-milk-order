package com.milk.order.module.stats.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 仪表盘总览 VO
 */
@Data
public class DashboardVO implements Serializable {

    /** 订单总数 */
    private Long totalOrders;

    /** 在订学生数（有有效订单的学生） */
    private Long activeStudents;

    /** 本月销售额（已支付订单实付金额之和） */
    private BigDecimal monthlySales;

    /** 库存预警数 */
    private Long warningCount;
}
