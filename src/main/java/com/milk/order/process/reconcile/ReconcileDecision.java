package com.milk.order.process.reconcile;

import lombok.Builder;
import lombok.Getter;

/**
 * 探测器输出：一个候选主体命中了一条补偿规则（“该补偿了”），尚未执行。
 *
 * <p>探测与执行分离，使「发现漂移」成为一个只读、可重复、可观测的动作：
 * 探测本身不改变任何业务状态，因此可以随时调用（管理端预览、体检、实验）。</p>
 */
@Getter
@Builder
public class ReconcileDecision {

    /** 命中的补偿规则 */
    private final ProcessReconcileRule rule;

    /** 待补偿的父过程实例 */
    private final ProcessCandidate candidate;
}
