package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 配送例外表（周末停送与调休例外，管理员手动维护）。
 *
 * <p>只描述「与日历默认规则不同」的日期，不做官方节假日的任何自动推算
 * （官方调休每年发布、各地不同，全部由管理员维护）：</p>
 * <ul>
 *   <li>{@code type=1} 停送：默认要送但不送（工作日放假）→ 该日任务并入前一个有效配送日；</li>
 *   <li>{@code type=2} 补送/补课：默认不送但要送（周末调休上课）→ 该日保留任务、正常配送。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_exception")
public class DeliveryException extends BaseEntity {

    /** 停送（默认要送但不送） */
    public static final int TYPE_STOP = 1;

    /** 补送/补课（默认不送但要送） */
    public static final int TYPE_MAKE_UP = 2;

    /** 例外日期（唯一） */
    private LocalDate exceptionDate;

    /** 类型：1-停送，2-补送 */
    private Integer type;

    /** 备注（如"五一调休""6/13 补课"） */
    private String remark;
}
