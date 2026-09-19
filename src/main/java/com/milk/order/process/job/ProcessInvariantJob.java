package com.milk.order.process.job;

import com.milk.order.module.system.service.SysConfigService;
import com.milk.order.process.invariant.InvariantScanReport;
import com.milk.order.process.invariant.ProcessInvariantScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过程不变量体检任务：周期校验全部跨表不变量并修复可自动修复项。
 *
 * <p>它的存在本身就是一条设计主张：<b>不变量只在被验证时才成立</b>。
 * 实验能证明不变量在某次运行中成立过，但业务过程是持续的，
 * 因此把“跨表约束”变成周期求值的检查项，让不一致在产生的当天就被发现，而不是等答辩时被人翻出来。</p>
 *
 * <p>可通过系统参数 {@code process.invariant.scan.enabled} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ProcessInvariantJob {

    /** 单项不变量单轮最多检出条数 */
    private static final int BATCH_LIMIT = 200;

    private final ProcessInvariantScanner invariantScanner;
    private final SysConfigService sysConfigService;

    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 600_000L)
    public void scan() {
        if (!sysConfigService.getBool("process.invariant.scan.enabled", true)) {
            return;
        }
        try {
            InvariantScanReport report = invariantScanner.scan(true, BATCH_LIMIT);
            if (report.isAllPassed()) {
                log.info("[不变量体检] 全部通过（检出 0）");
            } else {
                log.warn("[不变量体检] {}", report.summary());
            }
        } catch (Exception e) {
            log.error("[不变量体检] 执行异常", e);
        }
    }
}
