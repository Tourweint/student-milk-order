package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 拒收补送补偿台账。
 *
 * <p>一个被拒收的任务只允许产生一次补偿：{@code source_task_id} 唯一键仲裁。
 * {@code target_task_id} 记录补送落账的目标任务（套餐订单合并到次日任务 / 零散订单新建的任务），
 * 摊平算法据此还原「该任务基础量 = 1 + 补送量」，避免重置时把补送的 +1 抹掉。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_compensation")
public class DeliveryCompensation extends BaseEntity {

    /** 被拒收的原任务 ID */
    private Long sourceTaskId;

    /** 补送落账的目标任务 ID */
    private Long targetTaskId;

    /** 订单 ID */
    private Long orderId;

    /** 奶品 ID */
    private Long productId;

    /** 补送盒数 */
    private Integer boxes;

    /** 补送目标日期（次日） */
    private LocalDate compensationDate;

    /** 备注 */
    private String remark;
}
