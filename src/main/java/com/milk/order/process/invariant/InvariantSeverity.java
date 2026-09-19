package com.milk.order.process.invariant;

/**
 * 不变量违规的处置等级：决定体检发现问题后「能不能自动修」。
 *
 * <p>分级的判断标准是<b>修复的可逆性与影响面</b>：
 * 能由既有数据唯一推导出正确值、且重算幂等的，才允许自动修复；
 * 一旦涉及资金流向或需要人做业务判断的（如“订单已支付却查不到成功流水”），只告警、不自动改。</p>
 */
public enum InvariantSeverity {

    /** 可自动修复：正确值能从既有台账唯一推导，重算幂等且可回退 */
    AUTO_REPAIR("可自动修复"),

    /** 仅告警：涉及资金/业务判断，必须人工核处 */
    ALERT_ONLY("仅告警（需人工）");

    private final String text;

    InvariantSeverity(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
