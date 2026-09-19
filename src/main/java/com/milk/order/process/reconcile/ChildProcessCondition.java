package com.milk.order.process.reconcile;

import java.util.Arrays;

/**
 * 子过程条件编码（补偿规则中的“探测条件”）。
 *
 * <p><b>为什么条件不配置化：</b>补偿规则里可配置的是「用哪个条件、补偿到什么状态」，
 * 而条件本身的语义（什么算“全部到达终态”）涉及业务边界，必须由代码显式枚举并实现，
 * 否则规则表就能表达任意含义、无法审阅也无法测试。这是本设计刻意保留的边界。</p>
 */
public enum ChildProcessCondition {

    /** 该父过程下没有任何子过程 */
    NONE("无子任务"),

    /** 存在处于配送中的子任务（说明联动推进丢失） */
    HAS_DISPATCHING_TASK("存在配送中的子任务"),

    /** 全部子任务都已到达终态（已完成/已取消），且至少有一条子任务 */
    ALL_TASKS_TERMINAL("子任务已全部到达终态"),

    /** 全部子任务都已取消，且至少有一条子任务 */
    ALL_TASKS_CANCELLED("子任务已全部取消");

    private final String text;

    ChildProcessCondition(String text) {
        this.text = text;
    }

    /** 中文说明，用于日志与体检明细 */
    public String getText() {
        return text;
    }

    /**
     * 由数据库中的编码解析为枚举。
     *
     * @throws IllegalArgumentException 编码非法（规则表配错时快速失败，而不是静默跳过）
     */
    public static ChildProcessCondition fromCode(String code) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知的子过程条件编码：" + code + "（可选：" + Arrays.toString(values()) + "）"));
    }
}
