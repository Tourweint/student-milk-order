package com.milk.order.process.invariant;

import lombok.Builder;
import lombok.Getter;

/**
 * 单条不变量的体检结果（一轮体检中该项的统计）。
 */
@Getter
@Builder
public class InvariantScanResult {

    /** 不变量编码 */
    private final String code;

    /** 不变量说明 */
    private final String description;

    /** 处置等级 */
    private final InvariantSeverity severity;

    /** 本轮检出条数 */
    private final int detected;

    /** 其中首次检出（新开记录）条数 */
    private final int opened;

    /** 其中“已闭环后再次漂移”条数（自愈有效性指标） */
    private final int reopened;

    /** 自动修复生效条数 */
    private final int repaired;

    /** 修复未生效/异常条数 */
    private final int unrepaired;

    /** 复检闭环条数（上轮未闭环、本轮未再检出） */
    private final int closed;

    /** 探测自身异常信息（非空表示该项本轮未得出可信结论） */
    private final String error;

    /** 单行摘要，便于日志与实验输出 */
    public String summary() {
        if (error != null) {
            return String.format("%s（%s）探测异常：%s", code, severity.getText(), error);
        }
        return String.format("%s（%s）检出 %d，新开 %d，重复漂移 %d，修复 %d，未修复 %d，复检闭环 %d",
                code, severity.getText(), detected, opened, reopened, repaired, unrepaired, closed);
    }
}
