package com.milk.order.common.enums;

/**
 * 库存变动类型
 */
public enum InventoryChangeType {

    INBOUND(1, "入库", true),
    OUTBOUND(2, "出库", false),
    PROFIT(3, "盘盈", true),
    LOSS(4, "盘亏", false);

    private final int code;
    private final String desc;
    /** 是否为增加库存方向 */
    private final boolean increase;

    InventoryChangeType(int code, String desc, boolean increase) {
        this.code = code;
        this.desc = desc;
        this.increase = increase;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public boolean isIncrease() {
        return increase;
    }

    public static InventoryChangeType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (InventoryChangeType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
