package com.milk.order.process.job;

import com.milk.order.module.system.service.SysConfigService;
import com.milk.order.process.pending.ProcessPendingTask;
import com.milk.order.process.pending.ProcessPendingTaskService;
import com.milk.order.process.reconcile.ProcessReconcileCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 过程实时自愈消费者（双通道中的实时通道）。
 *
 * <p>秒级消费自愈待办：子过程只要有状态变更（送出/签收/拒收/取消），同一事务内就落下待办，
 * 因此父过程聚合不再依赖「调用方记得调用聚合出口」，而是由数据保证会被处理。</p>
 *
 * <p><b>与兜底通道的关系：</b>本通道把不一致的**不可检测窗口**从兜底任务的分钟级压到秒级；
 * 但自愈本身也会失败，超重试上限的待办会被放弃并交由 {@code OrderProcessReconcileJob} 的
 * 周期扫描兜住——两条通道缺一不可。</p>
 *
 * <p>可通过系统参数 {@code process.pending.enabled} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ProcessPendingTaskJob {

    /** 单轮最多处理条数（防止积压把一次调度拖成长事务） */
    private static final int BATCH_LIMIT = 200;

    /** 单条待办最大重试次数，超过则放弃并转兜底通道（实验八直接引用该上限，保证实验与生产策略同源） */
    public static final int MAX_RETRY = 5;

    /** 已处理待办的保留天数 */
    private static final int PURGE_RETENTION_DAYS = 7;

    /** 每 N 轮清理一次历史待办（≈1 小时一次） */
    private static final int PURGE_EVERY_ROUNDS = 720;

    private final ProcessPendingTaskService pendingTaskService;
    private final ProcessReconcileCoordinator reconcileCoordinator;
    private final SysConfigService sysConfigService;

    private final AtomicInteger rounds = new AtomicInteger();

    @Scheduled(fixedDelay = 5_000L, initialDelay = 15_000L)
    public void consume() {
        if (!sysConfigService.getBool("process.pending.enabled", true)) {
            return;
        }
        try {
            List<ProcessPendingTask> due = pendingTaskService.claimDue(BATCH_LIMIT);
            if (due.isEmpty()) {
                purgeOccasionally();
                return;
            }
            int handled = 0;
            int abandoned = 0;
            for (ProcessPendingTask task : due) {
                if (consumeOne(task)) {
                    handled++;
                } else {
                    abandoned++;
                }
            }
            log.info("[实时自愈] 本轮处理待办 {} 条，放弃 {} 条", handled, abandoned);
            purgeOccasionally();
        } catch (Exception e) {
            log.error("[实时自愈] 消费待办异常", e);
        }
    }

    /**
     * 处理单条待办。
     *
     * @return true 表示已处理完成（含“重新探测后未发现漂移”的已收敛情形）；false 表示达重试上限被放弃
     */
    public boolean consumeOne(ProcessPendingTask task) {
        try {
            reconcileCoordinator.reconcileEntity(task.getScene(), task.getEntityId());
            pendingTaskService.markDone(task.getId());
            return true;
        } catch (Exception e) {
            boolean abandoned = pendingTaskService.markRetry(task, e.getMessage(), MAX_RETRY);
            if (abandoned) {
                log.warn("[实时自愈] 待办 #{}（{} #{}）重试 {} 次仍失败，放弃并交由兜底通道：{}",
                        task.getId(), task.getScene(), task.getEntityId(), MAX_RETRY, e.getMessage());
            } else {
                log.debug("[实时自愈] 待办 #{} 处理失败，稍后重试：{}", task.getId(), e.getMessage());
            }
            return !abandoned;
        }
    }

    private void purgeOccasionally() {
        if (rounds.incrementAndGet() % PURGE_EVERY_ROUNDS == 0) {
            int purged = pendingTaskService.purgeHandled(PURGE_RETENTION_DAYS);
            if (purged > 0) {
                log.info("[实时自愈] 清理历史待办 {} 条（保留 {} 天）", purged, PURGE_RETENTION_DAYS);
            }
        }
    }
}
