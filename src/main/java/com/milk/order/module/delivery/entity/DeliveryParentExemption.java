package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 家长端「当日豁免」次数台账（学生 × 自然月一行）
 *
 * <p>语义：家长当天临时不要这份奶（病假、外出等）→ 取消该学生**当天尚未送出**的待配送任务，
 * 每月最多 {@code delivery.parent.exemption.monthly-limit} 次（默认 3）。</p>
 *
 * <p><b>为什么是"一行一个计数器"而不是"数明细行"</b>：次数上限是**并发敏感的资源**，
 * `COUNT(*) &lt; N` 的判定在并发下会超限（读-改-写），与配额池是同一类问题。
 * 因此这里按 `(student_id, exempt_month)` 建一行计数器，用「行锁读 + `used_count + 1 &lt;= limit` 条件更新」
 * 保证严格不超限；豁免动作与计数在**同一事务**内，动作失败则次数自动回退。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_parent_exemption")
public class DeliveryParentExemption extends BaseEntity {

    /** 学生 ID */
    private Long studentId;

    /** 自然月（YYYY-MM） */
    private String exemptMonth;

    /** 当月已用豁免次数 */
    private Integer usedCount;
}
