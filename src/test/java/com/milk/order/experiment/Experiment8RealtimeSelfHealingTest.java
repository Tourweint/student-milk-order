package com.milk.order.experiment;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.process.job.ProcessPendingTaskJob;
import com.milk.order.process.pending.ProcessPendingTask;
import com.milk.order.process.reconcile.ReconcileOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验八：实时自愈通道与兜底通道（双通道）。
 *
 * <p>重构前的不一致检测是纯拉模式：兜底任务每 10 分钟全表扫一轮，因此漂移的
 * <b>不可检测窗口最长 10 分钟</b>。本实验验证实时通道把该窗口压到「轮询间隔 + 修复耗时」量级，
 * 同时验证实时通道自身的失败路径：退避、超限放弃、以及放弃后由兜底通道兜住。</p>
 *
 * <p>说明：本实验直接调用消费者使用的那组方法（领取待办 → 补偿 → 标记完成），
 * 与 {@link ProcessPendingTaskJob} 的循环体是同一组调用；因此测得的修复耗时是真实的，
 * 端到端窗口只多出一个轮询间隔（5 秒）。</p>
 */
@DisplayName("实验八：实时自愈通道与兜底通道")
class Experiment8RealtimeSelfHealingTest extends ExperimentSupport {

    /** 实时通道的轮询间隔（与 ProcessPendingTaskJob 的 @Scheduled 保持一致） */
    private static final long REALTIME_POLL_INTERVAL_MS = 5_000L;

    /** 兜底通道的调度间隔（与 OrderProcessReconcileJob 的 @Scheduled 保持一致） */
    private static final long FALLBACK_INTERVAL_MS = 600_000L;

    @Test
    @DisplayName("实时通道毫秒级完成修复，检测窗口较兜底通道缩短两个数量级")
    void realtimeChannelConvergesFarFasterThanFallback() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        // 制造漂移：子任务已在配送中，父订单仍为已支付
        jdbcTemplate.update("UPDATE delivery_task SET status = 2 WHERE order_id = ?", orderId);

        // 实时通道：子过程状态变更时在事务内入队（此处单独调用，等价于业务方法里的同一处调用）
        long t0 = System.nanoTime();
        pendingTaskService.enqueue(StateTransitions.SCENE_ORDER, "order_info", orderId, null,
                StateTransitions.ACTION_TASK_CANCEL);
        long enqueueMs = (System.nanoTime() - t0) / 1_000_000L;

        // 消费者的一轮处理
        long t1 = System.nanoTime();
        List<ProcessPendingTask> due = pendingTaskService.claimDue(200);
        assertThat(due).hasSize(1);
        ProcessPendingTask task = due.get(0);
        ReconcileOutcome outcome = reconcileCoordinator.reconcileEntity(task.getScene(), task.getEntityId());
        pendingTaskService.markDone(task.getId());
        long repairMs = (System.nanoTime() - t1) / 1_000_000L;

        int statusAfterRealtime = orderStatus(orderId);
        Integer taskRowStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_pending_task WHERE id = ?", Integer.class, task.getId());

        long realtimeWindowMs = REALTIME_POLL_INTERVAL_MS + repairMs;
        report("实验八 · 对照A：实时通道 vs 兜底通道",
                "待办入队耗时(ms)", enqueueMs,
                "待办领取 + 补偿 + 标记完成耗时(ms)", repairMs,
                "命中的补偿规则", outcome.getRuleName(),
                "补偿后订单状态", statusAfterRealtime + "（3=配送中）",
                "待办终态（1=已处理）", taskRowStatus,
                "实时通道检测窗口上界(ms)", realtimeWindowMs + "（轮询 " + REALTIME_POLL_INTERVAL_MS + " + 修复）",
                "兜底通道检测窗口上界(ms)", FALLBACK_INTERVAL_MS,
                "窗口缩短倍数", FALLBACK_INTERVAL_MS / Math.max(1L, realtimeWindowMs));

        assertThat(statusAfterRealtime).isEqualTo(OrderStatus.DELIVERING.getCode());
        assertThat(outcome.isRepaired()).isTrue();
        assertThat(taskRowStatus).isEqualTo(1);
        assertThat(realtimeWindowMs).isLessThan(FALLBACK_INTERVAL_MS / 10);
    }

    @Test
    @DisplayName("待办按业务键去重：重复触发只留一条，不同触发动作各留一条")
    void enqueueIsDeduplicatedByBusinessKey() {
        long orderId = 123_456L;
        for (int i = 0; i < 10; i++) {
            pendingTaskService.enqueue(StateTransitions.SCENE_ORDER, "order_info", orderId, null,
                    StateTransitions.ACTION_TASK_CANCEL);
        }
        int sameTrigger = count("SELECT COUNT(*) FROM process_pending_task "
                + "WHERE scene = 'ORDER' AND entity_id = ? AND trigger_action = ?",
                orderId, StateTransitions.ACTION_TASK_CANCEL);

        pendingTaskService.enqueue(StateTransitions.SCENE_ORDER, "order_info", orderId, null,
                StateTransitions.ACTION_SIGN);
        int allTriggers = count("SELECT COUNT(*) FROM process_pending_task WHERE entity_id = ?", orderId);

        report("实验八 · 对照B：待办去重",
                "同一触发动作入队 10 次后的待办数", sameTrigger,
                "再入队另一触发动作后的待办数", allTriggers);

        assertThat(sameTrigger).isEqualTo(1);
        assertThat(allTriggers).isEqualTo(2);
    }

    @Test
    @DisplayName("实时通道失败按指数退避重试，超限后放弃并交由兜底通道")
    void failedPendingTaskRetriesWithBackoffThenAbandons() {
        // 未注册场景的待办必然处理失败：用于验证失败路径本身
        pendingTaskService.enqueue("UNKNOWN_SCENE", "unknown_table", 1L, null, "X");
        List<ProcessPendingTask> due = pendingTaskService.claimDue(10);
        assertThat(due).hasSize(1);
        long pendingId = due.get(0).getId();

        boolean abandoned = consumeExpectingFailure(due.get(0));
        // 退避生效：退避窗口内的待办不会被再次领取
        int claimableDuringBackoff = pendingTaskService.claimDue(10).size();
        LocalDateTime nextRetry = reloadPendingTask(pendingId).getNextRetryTime();
        int attempts = 1;

        while (!abandoned && attempts < 10) {
            abandoned = consumeExpectingFailure(reloadPendingTask(pendingId));
            attempts++;
        }

        ProcessPendingTask finalTask = reloadPendingTask(pendingId);
        String lastError = jdbcTemplate.queryForObject(
                "SELECT last_error FROM process_pending_task WHERE id = ?", String.class, pendingId);

        report("实验八 · 对照C：实时通道失败路径",
                "首次失败后是否进入退避（退避期可领取数应 0）", claimableDuringBackoff,
                "首次失败后的下次可处理时间", nextRetry,
                "尝试次数", attempts,
                "重试上限（与生产策略同源）", ProcessPendingTaskJob.MAX_RETRY,
                "最终状态（2=已放弃）", finalTask.getStatus(),
                "最终重试次数", finalTask.getRetryCount(),
                "最近一次失败原因", lastError);

        assertThat(claimableDuringBackoff).isZero();
        assertThat(nextRetry).isAfter(LocalDateTime.now());
        assertThat(abandoned).isTrue();
        assertThat(finalTask.getStatus()).isEqualTo(2);
        assertThat(finalTask.getRetryCount()).isEqualTo(ProcessPendingTaskJob.MAX_RETRY);
        assertThat(lastError).contains("未注册场景");
    }

    /** 消费一次并预期失败；失败时按生产策略记账并返回“是否已达上限被放弃” */
    private boolean consumeExpectingFailure(ProcessPendingTask task) {
        try {
            reconcileCoordinator.reconcileEntity(task.getScene(), task.getEntityId());
            return false;
        } catch (Exception e) {
            return pendingTaskService.markRetry(task, e.getMessage(), ProcessPendingTaskJob.MAX_RETRY);
        }
    }
}
