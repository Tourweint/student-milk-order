package com.milk.order.common.constant;

/**
 * 仓库台账账目类型（{@code warehouse_ledger.biz_type}）。
 *
 * <p>台账是**一条进出账**而不是库存系统：单表多类型，`SUM(CASE)` 即得余量
 * （见 `WarehouseService.balanceOf`）。方向由类型表达，而不是靠正负号——
 * 唯一例外是 {@link #ADJ}：它是纠错通道，必须能双向冲销，故其 {@code quantity} 带符号。</p>
 *
 * <p>设计依据：`docs/设计方案/2026-09-21-仓库余量与出库边界-设计方案.md`（口径以该文 §11 为准）。</p>
 */
public final class WarehouseBizType {

    /** 到货（人工登记：配送站收货点数时一行，可挂批次与送货单号） */
    public static final String IN = "IN";

    /** 送出（自动：任务开始配送 → 出库，挂 {@code delivery_task.id}） */
    public static final String OUT = "OUT";

    /** 退回（自动：拒收 → 回仓，挂 {@code delivery_record.id}） */
    public static final String IN_BACK = "IN_BACK";

    /** 修正（人工，仅管理员，必填原因；{@code quantity} 带符号：正=增加余量，负=减少余量） */
    public static final String ADJ = "ADJ";

    /** 期初（上线一次性登记，{@code receipt_no} 固定 {@code INIT}，可安全重跑） */
    public static final String INIT = "INIT";

    private WarehouseBizType() {
    }

    /**
     * 是否增加余量。
     *
     * <p>{@link #ADJ} 刻意不在此列：它带符号，方向由 {@code quantity} 表达，
     * 必须由 SQL 的 {@code ELSE quantity} 分支处理，不能按类型归入"进"或"出"。</p>
     */
    public static boolean isInbound(String bizType) {
        return IN.equals(bizType) || IN_BACK.equals(bizType) || INIT.equals(bizType);
    }
}
