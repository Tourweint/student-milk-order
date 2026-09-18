package com.milk.order.common.constant;

/**
 * 状态机场景与动作编码（对应 state_transition_rule 表的 scene/action 字段）
 *
 * 状态迁移是否允许由数据库规则表驱动，管理端可在线调整，无需改代码；
 * 未配置的组合默认视为禁止（白名单语义）。
 */
public final class StateTransitions {

    private StateTransitions() {
    }

    // ==================== 场景 ====================

    /** 订单状态机（order_info.status） */
    public static final String SCENE_ORDER = "ORDER";
    /** 配送任务状态机（delivery_task.status） */
    public static final String SCENE_DELIVERY_TASK = "DELIVERY_TASK";
    /** 续订计划状态机（subscription_plan.status） */
    public static final String SCENE_SUBSCRIPTION_PLAN = "SUBSCRIPTION_PLAN";
    /**
     * 配送记录子状态机（delivery_record.sign_status）
     *
     * <p>未纳入 state_transition_rule 规则表（属于任务状态机下挂的子状态），
     * 只做 CAS 条件更新与迁移留痕，见 TransitionSpec.ruleGoverned=false。</p>
     */
    public static final String SCENE_DELIVERY_RECORD = "DELIVERY_RECORD";

    // ==================== 订单动作 ====================

    /** 支付成功（模拟支付/微信回调，待支付→已支付） */
    public static final String ACTION_PAY = "PAY";
    /** 退订/取消（待支付、已支付→已退订） */
    public static final String ACTION_CANCEL = "CANCEL";
    /** 开始配送联动（已支付→配送中） */
    public static final String ACTION_DELIVER = "DELIVER";
    /** 配送任务全部终态自动完成（配送中→已完成） */
    public static final String ACTION_AUTO_COMPLETE = "AUTO_COMPLETE";
    /** 手动完成订单（配送中→已完成） */
    public static final String ACTION_COMPLETE = "COMPLETE";

    // ==================== 配送任务动作 ====================

    /** 开始配送/送出（待配送→配送中） */
    public static final String ACTION_DISPATCH = "DISPATCH";
    /** 任务取消（待配送/配送中→已取消） */
    public static final String ACTION_TASK_CANCEL = "TASK_CANCEL";
    /** 签收（配送中→已完成） */
    public static final String ACTION_SIGN = "SIGN";
    /** 拒收（配送中→已取消） */
    public static final String ACTION_REJECT = "REJECT";
    /** 缺货批量取消（仅待配送→已取消；已完成任务禁止回退） */
    public static final String ACTION_STOCKOUT_CANCEL = "STOCKOUT_CANCEL";

    // ==================== 续订计划动作 ====================

    /** 执行续订（默认仅已开启状态可续订） */
    public static final String ACTION_RENEW = "RENEW";
    /** 暂停续订（已开启→已暂停） */
    public static final String ACTION_PAUSE = "PAUSE";
    /** 恢复续订（已暂停→已开启） */
    public static final String ACTION_RESUME = "RESUME";
    /** 关闭续订（已开启/已暂停→已关闭） */
    public static final String ACTION_CLOSE = "CLOSE";
}
