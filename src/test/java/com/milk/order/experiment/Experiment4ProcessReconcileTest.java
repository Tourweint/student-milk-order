package com.milk.order.experiment;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.process.reconcile.ReconcileOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验四：父状态漂移的检测与补偿（异常恢复）。
 *
 * <p>「父子状态机」的正常路径是：子任务开始配送 → 父订单进入配送中；子任务全部终态 →
 * 父订单聚合为已完成。这两次推进都是普通的数据库写入，一旦进程中断、异常，
 * 或某条路径漏掉联动调用，父状态就会与子任务集合不一致，系统进入一个“自洽但错误”的状态。</p>
 *
 * <p>重构后补偿不再由订单服务里的两个 if 硬编码实现，而是走过程层的
 * 「探测器 → 补偿规则表 → 统一迁移出口」，因此本实验验证的是同一条链路的
 * 端到端行为：能发现、能补偿、可重复执行而不产生额外变更。</p>
 */
@DisplayName("实验四：父子状态漂移的对账补偿")
class Experiment4ProcessReconcileTest extends ExperimentSupport {

    private static final int BATCH_LIMIT = 200;

    @Test
    @DisplayName("人为制造父状态漂移，补偿任务修复回合法状态且幂等")
    void reconcileRepairsParentStatusDrift() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 1000);
        long orderId = newPendingOrder(date, date, 1);

        // 正常支付：订单 1→2，并按周期展开子任务（此单为单日单品种，共 1 条任务）
        orderInfoService.payOrder(orderId);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAID.getCode());
        int tasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        assertThat(tasks).isEqualTo(1);

        // ===== 漂移一：子任务已送出，父订单仍停在“已支付”（联动写入丢失）=====
        jdbcTemplate.update("UPDATE delivery_task SET status = 2 WHERE order_id = ?", orderId);
        int statusBeforeDrift1 = orderStatus(orderId);
        List<ReconcileOutcome> outcomes1 = reconcileCoordinator.reconcile(
                StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusAfterDrift1 = orderStatus(orderId);
        long repaired1 = outcomes1.stream().filter(ReconcileOutcome::isRepaired).count();

        // ===== 漂移二：子任务全部终态，父订单仍停在“配送中”（聚合写入丢失）=====
        jdbcTemplate.update("UPDATE delivery_task SET status = 3 WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE delivery_record SET sign_status = 1 WHERE task_id IN "
                + "(SELECT id FROM delivery_task WHERE order_id = ?)", orderId);
        int statusBeforeDrift2 = orderStatus(orderId);
        List<ReconcileOutcome> outcomes2 = reconcileCoordinator.reconcile(
                StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusAfterDrift2 = orderStatus(orderId);
        long repaired2 = outcomes2.stream().filter(ReconcileOutcome::isRepaired).count();

        String rule1 = outcomes1.isEmpty() ? "（无）" : outcomes1.get(0).getRuleName();
        String rule2 = outcomes2.isEmpty() ? "（无）" : outcomes2.get(0).getRuleName();

        // ===== 幂等验证：状态已合法后再跑一轮，不应产生任何新的迁移 =====
        int appliedBefore = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND result = 1", orderId);
        List<ReconcileOutcome> outcomes3 = reconcileCoordinator.reconcile(
                StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int appliedAfter = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND result = 1", orderId);

        report("实验四：父子状态漂移的对账补偿",
                "漂移一 · 补偿前订单状态", statusBeforeDrift1 + "（2=已支付）",
                "漂移一 · 命中规则", rule1,
                "漂移一 · 补偿后订单状态", statusAfterDrift1 + "（3=配送中）",
                "漂移一 · 补偿生效数", repaired1,
                "漂移二 · 补偿前订单状态", statusBeforeDrift2 + "（3=配送中）",
                "漂移二 · 命中规则", rule2,
                "漂移二 · 补偿后订单状态", statusAfterDrift2 + "（4=已完成）",
                "漂移二 · 补偿生效数", repaired2,
                "补偿生效的订单迁移总条数", appliedBefore,
                "重复对账后再统计（应不变）", appliedAfter,
                "第三轮探测到的漂移数（应为 0）", outcomes3.size());

        assertThat(statusBeforeDrift1).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(statusAfterDrift1).isEqualTo(OrderStatus.DELIVERING.getCode());
        assertThat(repaired1).isGreaterThanOrEqualTo(1);

        assertThat(statusBeforeDrift2).isEqualTo(OrderStatus.DELIVERING.getCode());
        assertThat(statusAfterDrift2).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(repaired2).isGreaterThanOrEqualTo(1);

        // 幂等：状态已合法后再补偿，既无新增迁移，也不再探测到漂移
        assertThat(appliedAfter).isEqualTo(appliedBefore);
        assertThat(outcomes3).isEmpty();
    }
}
