package com.milk.order.process.invariant;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 不变量体检记录：每个（不变量 × 主体）保留一行“当前一致性状态”。
 *
 * <p>设计取舍：不按“每次检出”落一行，而按主体保留最新状态。
 * 原因是体检每轮都会重新求值，若按次追加，同一主体的反复漂移会在表里堆出多行，
 * 反而看不出“它现在到底一致不一致”。重复漂移用 {@code reopenCount} 计数表达——
 * 这恰好是衡量自愈有效性的指标。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("process_invariant_violation")
public class ProcessInvariantViolation extends BaseEntity {

    /** 不变量编码 */
    private String invariantCode;

    /** 处置等级：AUTO_REPAIR / ALERT_ONLY */
    private String severity;

    /** 违规主体表名 */
    private String entityType;

    /** 违规主体主键 */
    private Long entityId;

    /** 业务单号 */
    private String bizNo;

    /** 违规明细（期望值 vs 实际值） */
    private String detail;

    /** 0-未闭环，1-已闭环，2-人工忽略 */
    private Integer status;

    /** 重复漂移次数（闭合后再次被检出则累加） */
    private Integer reopenCount;

    /** 修复动作 / 闭环原因 / 最近一次修复失败原因 */
    private String repairAction;

    /** 最近一次检出时间 */
    private LocalDateTime detectedTime;

    /** 闭环时间 */
    private LocalDateTime handledTime;
}
