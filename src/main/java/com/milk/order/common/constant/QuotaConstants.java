package com.milk.order.common.constant;

/**
 * 配额池口径常量（**唯一来源**）。
 *
 * <p>为什么必须抽成常量：计划顺延窗口同时被三处口径引用——</p>
 * <ol>
 *   <li>{@code DailyQuotaServiceImpl}：扣减的"先过期先出"与剩余量计算；</li>
 *   <li>仓库侧的发行封顶 R5′ 与短交预警：池口径 {@code quota_date ∈ [D−(SHELF_DAYS−1), D]}；</li>
 *   <li>{@code INV_WAREHOUSE_COVERAGE} 不变量：同上。</li>
 * </ol>
 *
 * <p>若三处各写各的窗口，就会出现"扣减说能卖、封顶说不能"的静默错账，
 * 而且没有任何断言能发现——因此窗口只能有一个定义。</p>
 *
 * <p><b>语义</b>（2026-09-21 校正）：本窗口是商务约定的"供货计划顺延窗口"，
 * <b>不是牛奶的物理保质期</b>（本项目配送常温奶，实际保质期六个月）。</p>
 */
public final class QuotaConstants {

    /** 计划顺延窗口（天）：池子 D 的未售额度可结转到配送日 {@code D..D+SHELF_DAYS−1}，窗口外作废。 */
    public static final int SHELF_DAYS = 3;

    private QuotaConstants() {
    }
}
