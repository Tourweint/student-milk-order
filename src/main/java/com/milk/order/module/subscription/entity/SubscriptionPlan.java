package com.milk.order.module.subscription.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 续订计划表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("subscription_plan")
public class SubscriptionPlan extends BaseEntity {

    /** 学生 ID */
    private Long studentId;

    /** 家长用户 ID */
    private Long userId;

    /** 套餐 ID（续订时使用的套餐） */
    private Long packageId;

    /** 原订单 ID */
    private Long originalOrderId;

    /** 续订周期：1-每月续订 */
    private Integer cycleType;

    /** 状态：0-已关闭，1-已开启 */
    private Integer status;

    /** 下次续订日期 */
    private LocalDateTime nextRenewalTime;

    /** 上次续订时间 */
    private LocalDateTime lastRenewalTime;

    /** 续订提醒是否已发送：0-否，1-是 */
    private Integer reminderSent;

    /** 备注 */
    private String remark;
}
