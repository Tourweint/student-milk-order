package com.milk.order.process.reconcile;

import lombok.Builder;
import lombok.Getter;

/**
 * 一次补偿执行结果（探测器输出的决策被执行后的落点）。
 */
@Getter
@Builder
public class ReconcileOutcome {

    /** 父过程主键 */
    private final Long entityId;

    /** 业务单号 */
    private final String bizNo;

    /** 命中的规则名；为 null 表示未发现漂移（无需补偿） */
    private final String ruleName;

    /** 补偿动作 */
    private final String action;

    /** 补偿前状态 */
    private final Integer fromStatus;

    /** 补偿后状态（规则声明的目标状态） */
    private final Integer toStatus;

    /** 本次是否真的发生了状态迁移 */
    private final boolean repaired;

    /** 执行是否异常（实时通道据此决定重试） */
    private final boolean failed;

    /** 结论说明（用于日志与实验输出） */
    private final String message;

    /** 未发现漂移 */
    public static ReconcileOutcome noDrift(Long entityId, String bizNo) {
        return ReconcileOutcome.builder()
                .entityId(entityId).bizNo(bizNo)
                .repaired(false).failed(false)
                .message("未发现漂移，无需补偿")
                .build();
    }

    /** 单行摘要，便于实验与日志输出 */
    public String summary() {
        if (ruleName == null) {
            return String.format("#%s%s：%s", entityId, bizNo == null ? "" : "(" + bizNo + ")", message);
        }
        return String.format("#%s%s：命中「%s」%s → %s，%s",
                entityId, bizNo == null ? "" : "(" + bizNo + ")", ruleName, fromStatus, toStatus,
                failed ? "执行异常：" + message : (repaired ? "补偿生效" : message));
    }
}
