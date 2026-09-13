package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 每日机动配额扣减台账（按订单×品种×池子日期记录扣减盒数，供退订精确回补）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("daily_quota_usage")
public class DailyQuotaUsage extends BaseEntity {

    /** 订单 ID */
    private Long orderId;

    /** 奶品 ID */
    private Long productId;

    /** 被扣减的配额日期（池子所属日期） */
    private LocalDate quotaDate;

    /** 扣减盒数 */
    private Integer boxes;
}
