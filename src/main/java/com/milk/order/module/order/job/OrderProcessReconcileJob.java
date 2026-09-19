package com.milk.order.module.order.job;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.module.system.service.SysConfigService;
import com.milk.order.process.reconcile.ProcessReconcileCoordinator;
import com.milk.order.process.reconcile.ReconcileOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务过程聚合对账补偿任务（兜底通道）。
 *
 * <p>正常路径下，父订单状态由子过程驱动：任务开始配送时联动订单进入「配送中」，
 * 任务全部到达终态时聚合为「已完成」；并且每次子过程状态变更都会在实时通道留下待办。
 * 但实时通道也会失败（超重试上限即放弃），因此仍需要一条周期扫描的兜底通道：
 * <b>实时通道追求收敛速度，兜底通道保证最终一致</b>，两者缺一不可。</p>
 *
 * <p>本任务只负责「触发一轮批量补偿」，具体补偿哪一类漂移、补偿成什么状态，
 * 由 {@code process_reconcile_rule} 规则表决定，因此新增一类漂移无需改这里。
 * 可通过系统参数 {@code order.process.reconcile.enabled} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderProcessReconcileJob {

    private final ProcessReconcileCoordinator reconcileCoordinator;
    private final SysConfigService sysConfigService;

    /** 单轮单类最大处理量，防止历史脏数据把任务拖死 */
    private static final int BATCH_LIMIT = 200;

    @Scheduled(fixedDelay = 600_000L, initialDelay = 300_000L)
    public void reconcile() {
        if (!sysConfigService.getBool("order.process.reconcile.enabled", true)) {
            return;
        }
        try {
            List<ReconcileOutcome> outcomes = reconcileCoordinator.reconcile(
                    StateTransitions.SCENE_ORDER, BATCH_LIMIT);
            long repaired = outcomes.stream().filter(ReconcileOutcome::isRepaired).count();
            long failed = outcomes.stream().filter(ReconcileOutcome::isFailed).count();
            if (repaired > 0 || failed > 0) {
                log.info("[过程对账] 本轮探测到漂移 {} 条：补偿生效 {}，异常 {}",
                        outcomes.size(), repaired, failed);
            }
            outcomes.stream().filter(ReconcileOutcome::isRepaired)
                    .forEach(outcome -> log.info("[过程对账] {}", outcome.summary()));
        } catch (Exception e) {
            log.error("[过程对账] 执行异常", e);
        }
    }
}
