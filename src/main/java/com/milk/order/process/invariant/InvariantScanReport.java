package com.milk.order.process.invariant;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮不变量体检报告（可直接打印进日志与实验文档）。
 */
@Getter
public class InvariantScanReport {

    /** 体检时间 */
    private final LocalDateTime scanTime;

    /** 各项结果 */
    private final List<InvariantScanResult> results;

    /** 检出合计 */
    private final int totalDetected;

    /** 自动修复生效合计 */
    private final int totalRepaired;

    /** 未闭环合计（含修复失败、降级为仅告警与需人工核处） */
    private final int totalOpen;

    /** 待人工合计（降级为仅告警 + 不变量默认等级即为仅告警的检出） */
    private final int totalAlerts;

    /** 重复漂移合计 */
    private final int totalReopened;

    /** 复检闭环合计 */
    private final int totalClosed;

    private InvariantScanReport(LocalDateTime scanTime, List<InvariantScanResult> results) {
        this.scanTime = scanTime;
        this.results = List.copyOf(results);
        this.totalDetected = results.stream().mapToInt(InvariantScanResult::getDetected).sum();
        this.totalRepaired = results.stream().mapToInt(InvariantScanResult::getRepaired).sum();
        this.totalReopened = results.stream().mapToInt(InvariantScanResult::getReopened).sum();
        this.totalClosed = results.stream().mapToInt(InvariantScanResult::getClosed).sum();
        // 待人工 = 按每条违规的实际等级统计的仅告警条数（不变量默认等级为告警的，其违规也带同一等级，只计一次）
        this.totalAlerts = results.stream().mapToInt(InvariantScanResult::getAlerts).sum();
        this.totalOpen = totalAlerts + results.stream().mapToInt(InvariantScanResult::getUnrepaired).sum();
    }

    public static InvariantScanReport of(List<InvariantScanResult> results) {
        return new InvariantScanReport(LocalDateTime.now(), results);
    }

    /** 取某不变量的结果；未注册该编码时返回 null（便于测试与管理端断言） */
    public InvariantScanResult resultOf(String code) {
        return results.stream().filter(r -> r.getCode().equals(code)).findFirst().orElse(null);
    }

    /** 是否全部通过（无任何检出） */
    public boolean isAllPassed() {
        return totalDetected == 0;
    }

    /** 多行摘要 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("不变量体检 @%s：检出 %d，自动修复 %d，未闭环 %d，重复漂移 %d，复检闭环 %d",
                scanTime, totalDetected, totalRepaired, totalOpen, totalReopened, totalClosed));
        for (InvariantScanResult result : results) {
            sb.append(System.lineSeparator()).append("  · ").append(result.summary());
        }
        return sb.toString();
    }
}
