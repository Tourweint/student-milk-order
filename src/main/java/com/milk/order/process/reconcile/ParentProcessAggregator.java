package com.milk.order.process.reconcile;

/**
 * 父过程聚合器（各业务模块实现并注册）：把一条补偿决策落到父过程状态上。
 *
 * <p><b>实现约定：</b>必须复用统一迁移出口（{@code ProcessTransitionExecutor.attempt}）或
 * 业务侧的聚合出口完成状态迁移，从而天然幂等、天然留痕；实现内部不得自行拼 SQL 直接改状态，
 * 否则补偿会成为绕过可靠性层的旁路。</p>
 *
 * <p><b>并发语义：</b>补偿与正常业务路径可能同时发生（例如补偿正在进行时用户正好退订），
 * 因此实现必须是「以期望来源状态为条件的条件更新」——竞态下只有一方生效，另一方返回 false。</p>
 */
public interface ParentProcessAggregator {

    /** 父过程场景，见 StateTransitions.SCENE_* */
    String scene();

    /**
     * 执行补偿。
     *
     * @param rule    命中的补偿规则（携带动作与目标状态）
     * @param parent  待补偿的父过程实例
     * @return true 表示本次补偿生效；false 表示状态已被并发变更或规则表禁止（两者都无需重试）
     */
    boolean compensate(ProcessReconcileRule rule, ProcessCandidate parent);
}
