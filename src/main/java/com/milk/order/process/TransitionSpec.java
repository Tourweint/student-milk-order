package com.milk.order.process;

import lombok.Builder;
import lombok.Getter;

/**
 * 一次受控状态迁移的规格（过程层输入模型）。
 *
 * <p>把「哪个业务对象、在哪个场景、执行哪个动作、从什么状态到什么状态、冲突时如何提示」
 * 收拢为一个显式规格，交给 {@link ProcessTransitionExecutor} 统一执行。这样状态迁移就从
 * 散落在各 Service 里的 if 判断 + 条件更新语句，上升为可校验、可留痕的“过程定义”。</p>
 *
 * @see ProcessTransitionExecutor
 */
@Getter
@Builder
public class TransitionSpec {

    /** 状态机场景（见 StateTransitions.SCENE_*） */
    private final String scene;

    /** 动作编码（见 StateTransitions.ACTION_*） */
    private final String action;

    /** 场景中文名，规则禁止时拼入错误提示，如“订单”“配送任务” */
    private final String sceneText;

    /** 迁移主体的数据表名，用于迁移台账归类，如 order_info */
    private final String entityType;

    /** 迁移主体主键 */
    private final Long entityId;

    /** 业务单号（可选，便于人工回溯），如订单号、任务号 */
    private final String bizNo;

    /** 迁移前状态（同时作为 CAS 条件） */
    private final Integer fromStatus;

    /** 迁移后状态 */
    private final Integer toStatus;

    /**
     * 是否受 state_transition_rule 规则表管控。
     * false 表示该子状态机未纳入规则表（如 delivery_record.sign_status），
     * 只做 CAS 与留痕，不做规则校验。
     */
    @Builder.Default
    private final boolean ruleGoverned = true;

    /** CAS 冲突（状态已被并发修改）时的业务提示 */
    private final String conflictMessage;

    /** 操作人；为空时取当前登录用户，仍为空则记为 system */
    private final String operator;

    /** 迁移备注（写入台账） */
    private final String remark;
}
