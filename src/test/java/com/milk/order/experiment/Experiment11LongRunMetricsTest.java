package com.milk.order.experiment;

import com.milk.order.chaos.ChaosInjector;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.process.invariant.InvariantScanReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十一：长稳对账度量——混合负载下「三通道」的工作量与检测延迟。
 *
 * <p>实验六~十各自验证了单个机制；本实验把它们放进**同一段时间里同时工作**的场景，
 * 度量三件事：① 三条通道各自做了多少工作；② 检测延迟分布；③ 长时间运行后是否仍然一致。</p>
 *
 * <p><b>与"2~24 小时长稳"的关系（必须说清）</b>：真实长稳需要小时级运行，
 * 单测里做不到。本实验用的是**高密度压缩**：把"业务写入 + 人为漂移 + 三通道并发收敛"
 * 压在几秒内持续进行，度量口径与真实长稳完全一致（同一批 SQL 与同一组计数器），
 * 只是时间尺度被压缩。所以它能回答"机制在持续负载下是否稳定收敛"，
 * <b>不能</b>回答"运行 24 小时后有没有缓慢退化（例如缓存/表膨胀/连接泄漏）"——那仍属后续工作。</p>
 */
@DisplayName("实验十一：长稳对账度量——三通道工作量与检测延迟")
class Experiment11LongRunMetricsTest extends ExperimentSupport {

    private static final int LIMIT = 500;
    /** 压缩负载持续时长（毫秒） */
    private static final long DURATION_MS = 6000L;

    @Test
    @DisplayName("混合负载持续运行：三通道持续收敛，检测延迟有界")
    void mixedWorkloadKeepsConvergingWithBoundedLatency() throws Exception {
        ChaosInjector.disarm();
        LocalDate date = LocalDate.now().plusDays(1);
        int orderCount = 12;
        setQuota(date, 1000);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            orderIds.add(newPendingOrder(date, date, 1));
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger businessOps = new AtomicInteger();
        AtomicInteger businessConflicts = new AtomicInteger();
        AtomicInteger drifts = new AtomicInteger();
        AtomicInteger realtimeRounds = new AtomicInteger();
        AtomicInteger realtimeRepaired = new AtomicInteger();
        AtomicInteger fallbackRounds = new AtomicInteger();
        AtomicInteger fallbackRepaired = new AtomicInteger();
        AtomicInteger checkRounds = new AtomicInteger();
        AtomicInteger checkRepaired = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();

        // 业务线程：把订单推进到「已完成」（各自负责一部分订单，完成后轮空）
        int workers = 4;
        List<List<Long>> shards = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            shards.add(new ArrayList<>());
        }
        for (int i = 0; i < orderIds.size(); i++) {
            shards.get(i % workers).add(orderIds.get(i));
        }
        CountDownLatch startGate = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            List<Long> shard = shards.get(i);
            threads.add(daemonThread("exp11-business-" + i, startGate, () -> {
                for (Long orderId : shard) {
                    try {
                        if (orderStatus(orderId) == OrderStatus.PENDING_PAYMENT.getCode()) {
                            orderInfoService.payOrder(orderId);
                            businessOps.incrementAndGet();
                        }
                        if (orderStatus(orderId) == OrderStatus.PAID.getCode()) {
                            deliveryTaskService.batchStartDelivery(date.toString(), null);
                            businessOps.incrementAndGet();
                        }
                        if (orderStatus(orderId) == OrderStatus.DELIVERING.getCode()) {
                            long taskId = taskIdOf(orderId, date);
                            signRecord(recordIdOf(taskId));
                            businessOps.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // 与三通道并发操作同一批订单，CAS 冲突是预期内的
                        businessConflicts.incrementAndGet();
                    }
                }
            }));
        }

        // 漂移注入线程：持续把「已完成」订单回退为「配送中」，模拟聚合写入丢失
        // （只注入被规则表覆盖的漂移：配送中 + 子任务全终态 → 命中「聚合丢失补偿」/INV_ORDER_AGGREGATION）
        threads.add(daemonThread("exp11-drift", startGate, () -> {
            while (running.get()) {
                List<Long> completed = jdbcTemplate.queryForList(
                        "SELECT id FROM order_info WHERE status = ?", Long.class, OrderStatus.COMPLETED.getCode());
                for (Long orderId : completed) {
                    if (orderStatus(orderId) == OrderStatus.COMPLETED.getCode()) {
                        jdbcTemplate.update("UPDATE order_info SET status = ? WHERE id = ? AND status = ?",
                                OrderStatus.DELIVERING.getCode(), orderId, OrderStatus.COMPLETED.getCode());
                        drifts.incrementAndGet();
                    }
                }
                sleep(60);
            }
        }));

        // 实时通道线程：连续消费待办（等价于把 ProcessPendingTaskJob 的轮询间隔压到 0）
        threads.add(daemonThread("exp11-realtime", startGate, () -> {
            while (running.get()) {
                int repaired = drainPendingTasks(LIMIT);
                realtimeRounds.incrementAndGet();
                realtimeRepaired.addAndGet(repaired);
                sleep(10);
            }
        }));

        // 兜底通道线程
        threads.add(daemonThread("exp11-fallback", startGate, () -> {
            while (running.get()) {
                int repaired = 0;
                for (var outcome : reconcileCoordinator.reconcile(
                        com.milk.order.common.constant.StateTransitions.SCENE_ORDER, LIMIT)) {
                    if (outcome.isRepaired()) {
                        repaired++;
                    }
                }
                fallbackRounds.incrementAndGet();
                fallbackRepaired.addAndGet(repaired);
                sleep(40);
            }
        }));

        // 体检线程
        threads.add(daemonThread("exp11-invariant", startGate, () -> {
            while (running.get()) {
                InvariantScanReport report = invariantScanner.scan(true, LIMIT);
                checkRounds.incrementAndGet();
                checkRepaired.addAndGet(report.getTotalRepaired());
                sleep(120);
            }
        }));

        threads.forEach(Thread::start);
        startGate.countDown();
        Thread.sleep(DURATION_MS);
        running.set(false);
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // 停机后做一次收敛（等价于"负载停止后最后一轮兜底"），再断言最终一致
        int[] rounds = new int[1];
        int finalRepaired = convergeBothChannels(LIMIT, 10, rounds);
        InvariantScanReport finalScan = invariantScanner.scan(true, LIMIT);

        int completed = count("SELECT COUNT(*) FROM order_info WHERE status = ?", OrderStatus.COMPLETED.getCode());
        int illegal = count("SELECT COUNT(*) FROM order_info WHERE status NOT IN (2, 3, 4)");
        int duplicateRecords = count("SELECT COUNT(*) FROM (SELECT order_id FROM payment_record "
                + "WHERE status = 2 GROUP BY order_id HAVING COUNT(*) > 1) t");
        int duplicateLedger = count("SELECT COUNT(*) FROM (SELECT entity_id FROM process_transition_log "
                + "WHERE action = 'PAY' AND result = 1 GROUP BY entity_id HAVING COUNT(*) > 1) t");
        int intakes = count("SELECT COUNT(*) FROM nutrition_intake");
        int signLedger = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE action = 'SIGN' AND result = 1");
        int conflictLedger = count("SELECT COUNT(*) FROM process_transition_log WHERE result = 0");
        int pendingTotal = count("SELECT COUNT(*) FROM process_pending_task");
        int pendingDone = count("SELECT COUNT(*) FROM process_pending_task WHERE status = 1");
        int pendingAbandoned = count("SELECT COUNT(*) FROM process_pending_task WHERE status = 2");
        // 到期却仍未被处理的待办：正常收敛下应为 0（处于退避期的待处理项不算）
        int pendingDueNow = count("SELECT COUNT(*) FROM process_pending_task WHERE status = 0 "
                + "AND (next_retry_time IS NULL OR next_retry_time <= NOW())");
        int violations = count("SELECT COUNT(*) FROM process_invariant_violation");
        int reopened = count("SELECT COALESCE(SUM(reopen_count), 0) FROM process_invariant_violation");

        List<Double> latencies = jdbcTemplate.queryForList(
                "SELECT TIMESTAMPDIFF(MICROSECOND, create_time, update_time) / 1000.0 "
                        + "FROM process_pending_task WHERE status = 1 AND update_time IS NOT NULL",
                Double.class);

        report("实验十一 · 混合负载下的三通道工作量与检测延迟",
                "负载时长(ms) / 订单数", DURATION_MS + " / " + orderCount,
                "业务操作成功数 / 并发冲突数", businessOps.get() + " / " + businessConflicts.get(),
                "人为注入漂移次数", drifts.get(),
                "实时通道 · 轮次 / 消费待办并补偿生效数", realtimeRounds.get() + " / " + realtimeRepaired.get(),
                "兜底通道 · 轮次 / 补偿生效数", fallbackRounds.get() + " / " + fallbackRepaired.get(),
                "体检 · 轮次 / 自动修复生效数", checkRounds.get() + " / " + checkRepaired.get(),
                "停机后最终收敛 · 补偿生效数 / 轮次", finalRepaired + " / " + rounds[0],
                "迁移台账 · 生效条数 / CAS 冲突条数", signLedger + " / " + conflictLedger,
                "待办 · 总行数 / 已处理 / 已放弃 / 到期未处理",
                pendingTotal + " / " + pendingDone + " / " + pendingAbandoned + " / " + pendingDueNow,
                "体检记录 · 行数 / 重复漂移合计", violations + " / " + reopened,
                "检测延迟(ms) · 样本数 / p50 / p95 / max",
                latencies.size() + " / " + percentile(latencies, 50) + " / "
                        + percentile(latencies, 95) + " / " + round(max(latencies)),
                "最终 · 已完成订单数（应 = 订单数）", completed,
                "最终 · 非合法状态订单数（应 0）", illegal,
                "最终 · 重复成功流水 / 重复 PAY 台账的订单数（应 0 / 0）",
                duplicateRecords + " / " + duplicateLedger,
                "最终 · 营养摄入条数（应 = 订单数）", intakes,
                "最终 · 全库不变量检出数 / 未闭环数（应 0 / 0）",
                finalScan.getTotalDetected() + " / " + finalScan.getTotalOpen());

        // ① 三条通道都确实在工作（否则"收敛"没有说服力）
        assertThat(drifts.get()).isGreaterThan(0);
        assertThat(realtimeRepaired.get() + fallbackRepaired.get() + checkRepaired.get()).isGreaterThan(0);
        assertThat(realtimeRepaired.get()).isGreaterThan(0);
        assertThat(fallbackRepaired.get()).isGreaterThan(0);
        // ② 最终一致：所有订单走完生命周期，且没有重复副作用
        assertThat(completed).isEqualTo(orderCount);
        assertThat(illegal).isZero();
        assertThat(duplicateRecords).isZero();
        assertThat(duplicateLedger).isZero();
        assertThat(intakes).isEqualTo(orderCount);
        // ③ 全库不变量成立（体检自己也要无活可干）
        assertThat(finalScan.getTotalDetected()).isZero();
        assertThat(finalScan.getTotalOpen()).isZero();
        // ④ 没有"到期却无人处理"的待办，也没有被放弃的待办
        //    （处于指数退避期的待处理项不算：那是失败后的正常等待）
        assertThat(pendingDueNow).isZero();
        assertThat(pendingAbandoned).isZero();
        // ⑤ 检测延迟有界（这是"长稳"里最容易被忽略的指标：修得对，还要修得及时）
        assertThat(latencies).isNotEmpty();
        assertThat(percentile(latencies, 95)).isLessThan(3000.0);
    }

    @Test
    @DisplayName("已支付 + 子任务全终态：由补偿规则收敛（3.4 补齐前这里是覆盖盲区）")
    void paidOrderWithAllTasksTerminalIsCompensated() {
        ChaosInjector.disarm();
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        long taskId = taskIdOf(orderId, date);
        signRecord(recordIdOf(taskId));
        // 订单此刻为已支付（未联动），子任务已全部签收 —— 模拟"配送联动与聚合写入同时丢失"
        jdbcTemplate.update("UPDATE order_info SET status = ? WHERE id = ?",
                OrderStatus.PAID.getCode(), orderId);

        // 3.4 之前：无规则命中、双通道补 0、订单停在已支付、体检也看不出（特征化记录）；
        // 3.4 补齐后：命中「已支付全终态补偿」，双通道把订单聚合为已完成。
        // 注意先探测再收敛：收敛之后漂移已不存在，探测自然为空
        List<String> hits = reconcileCoordinator.detectEntity(
                com.milk.order.common.constant.StateTransitions.SCENE_ORDER, orderId).stream()
                .map(d -> d.getRule().getName()).toList();
        int charged = convergeBothChannels(LIMIT, 5, null);
        int statusAfterChannels = orderStatus(orderId);
        int detected = invariantScanner.scan(true, LIMIT).getTotalDetected();
        int completeLedger = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'AUTO_COMPLETE' "
                + "AND result = 1 AND from_status = 2",
                orderId);

        report("实验十一 · 规则覆盖盲区补齐后的回归（3.4）",
                "构造状态", "订单已支付(2) + 子任务全部已完成(3)",
                "命中规则", hits.isEmpty() ? "（无）" : hits,
                "双通道补偿生效数（应 1）", charged,
                "通道跑完后的订单状态（应 4=已完成）", statusAfterChannels,
                "补偿聚合（AUTO_COMPLETE 2→4）台账条数（应 1；签收时的正常聚合为 3→4，不计入）", completeLedger,
                "不变量体检检出数（应 0）", detected,
                "边界说明", "子任务全部取消时也会被本规则补为已完成；"
                        + "「全部取消→已退订」的区分交给默认停用的全取消补偿规则与退款设计");

        assertThat(hits).containsExactly("已支付全终态补偿");
        assertThat(charged).isEqualTo(1);
        assertThat(statusAfterChannels).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(completeLedger).isEqualTo(1);
        assertThat(detected).isZero();
    }

    // ==================== 工具 ====================

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return round(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0d);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
