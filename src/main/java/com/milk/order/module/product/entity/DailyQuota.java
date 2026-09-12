package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 每日机动配额（单日零散订购的当日可售余量，非传统库存）
 *
 * 业务口径：学期套餐为全校统一预约定制订单，与库存/配额无关；
 * 仅单日零散订购（临时补订、换口味、插班临时订购）占用当日机动配额，卖完即止，由管理员逐日设置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("daily_quota")
public class DailyQuota extends BaseEntity {

    /** 配额日期（唯一） */
    private LocalDate quotaDate;

    /** 当日机动总盒数（管理员设置） */
    private Integer totalQuota;

    /** 当日已售盒数（零散订购支付成功后累加，退订回补） */
    private Integer usedQuota;

    /** 备注 */
    private String remark;
}
