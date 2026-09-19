package com.milk.order.process.reconcile;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 过程补偿规则：发现「父过程状态 + 子过程条件」不满足时的补偿决策。
 *
 * <p>与 {@code state_transition_rule}（迁移是否允许）分工明确：</p>
 * <ul>
 *   <li>state_transition_rule 回答「这次迁移允不允许」，是迁移的闸门；</li>
 *   <li>本表回答「发现漂移时该把父过程补偿成什么状态」，是恢复的决策。</li>
 * </ul>
 *
 * <p>两者存在依赖关系：本表配出的补偿动作仍要过 state_transition_rule 的闸门，
 * 因此启用一条新补偿规则时，可能需要同步放开对应的迁移规则（见种子数据中的示例说明）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("process_reconcile_rule")
public class ProcessReconcileRule extends BaseEntity {

    /** 规则名（管理端展示） */
    private String name;

    /** 父过程场景，如 ORDER */
    private String parentScene;

    /** 父过程需满足的状态（候选筛选条件），如 2-已支付 */
    private Integer parentStatus;

    /** 子过程条件编码，见 ChildProcessCondition */
    private String childCondition;

    /** 补偿动作（复用 StateTransitions 动作码），如 DELIVER */
    private String action;

    /** 补偿后的父过程状态，如 3-配送中 */
    private Integer targetStatus;

    /** 是否启用：1-启用，0-停用 */
    private Integer enabled;

    /** 规则说明 */
    private String description;
}
