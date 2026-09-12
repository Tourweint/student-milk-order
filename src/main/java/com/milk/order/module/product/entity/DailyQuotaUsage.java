package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 每日机动配额扣减台账（按订单记录从各日池子扣减的盒数，供退订精确回补）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("daily_quota_usage")
public class DailyQuotaUsage extends BaseEntity {

    /** 订单 ID */
    private Long orderId;

    /** 被扣减的配额日期（池子所属日期） */
    private java.time.LocalDate quotaDate;

    /** 扣减盒数 */
    private Integer boxes;
}
