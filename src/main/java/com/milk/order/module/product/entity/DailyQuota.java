package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 每日机动配额（按品种设置的单日可售余量，非传统库存）
 *
 * 业务口径：学期套餐为全校统一预约定制订单，与配额无关；
 * 仅单日零散订购（临时补订、换口味、插班临时订购）占用所选日期、所选品种的机动配额，
 * 未售完自动结转（保质期窗口内），卖完即止，由管理员按日按品种设置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("daily_quota")
public class DailyQuota extends BaseEntity {

    /** 配额日期 */
    private LocalDate quotaDate;

    /** 奶品 ID（按品种设置） */
    private Long productId;

    /** 当日该品种机动总盒数（管理员设置） */
    private Integer totalQuota;

    /** 当日该品种已售盒数（零散订购支付成功后累加，退订回补） */
    private Integer usedQuota;

    /**
     * 可选：当日该品种的到货批次号（**批次追溯钩子**，仅作标注）。
     *
     * <p>刻意只作字符串标注、不做外键：批次不参与扣减、结转与台账口径，
     * 它的唯一用途是「批号 → 池子 → 台账 → 订单/学生」的召回反查（见 {@code ProductBatchService.trace}）。
     * 因此批号未建档也允许先标注（事故当场可直接反查），建档只提供生产/到货日期等元信息。</p>
     */
    private String batchNo;

    /** 备注 */
    private String remark;
}
