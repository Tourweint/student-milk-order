package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 家长端「当日豁免」概览：今天可豁免什么、本月还剩几次
 *
 * <p>供小程序在下单前判断按钮是否可用（不让家长点进去才被拒）。</p>
 */
@Data
public class ParentExemptionVO implements Serializable {

    /** 豁免针对的配送日（= 今天） */
    private LocalDate deliveryDate;

    /** 今天尚未送出、可被豁免的待配送任务数 */
    private Integer exemptableCount;

    /** 本月次数上限（sys_config `delivery.parent.exemption.monthly-limit`） */
    private Integer monthlyLimit;

    /** 本月已用次数 */
    private Integer usedCount;

    /** 本月剩余次数 */
    private Integer remaining;
}
