package com.milk.order.experiment;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.process.reconcile.ReconcileDecision;
import com.milk.order.process.reconcile.ReconcileOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验六：补偿规则引擎（规则表驱动 + 与迁移闸门的分工）。
 *
 * <p>重构前，「已支付 + 有配送中任务 → 配送中」这条补偿规则以 if 的形式硬编码在订单服务里：
 * 想改补偿目标、想临时停止补偿、想新增一类漂移，都必须改代码重新发布。</p>
 *
 * <p>重构后补偿规则下沉到 {@code process_reconcile_rule}（决策层），本实验验证三件事：</p>
 * <ol>
 *   <li>探测器能只读地命中规则、给出补偿决策（探测不产生副作用）；</li>
 *   <li>停用规则后同样的漂移不再被补偿 —— 补偿确实由数据驱动，而不是代码里写死的；</li>
 *   <li>补偿仍然要过 {@code state_transition_rule} 的迁移闸门 —— 配置化不会让补偿变成绕过可靠性层的旁路。</li>
 * </ol>
 */
@DisplayName("实验六：补偿规则引擎")
class Experiment6ReconcileRuleEngineTest extends ExperimentSupport {

    private static final int BATCH_LIMIT = 200;
    private static final String RULE_LINK_LOST = "联动丢失补偿";

    @Test
    @DisplayName("漂移命中规则表决策；停用规则即不补偿；迁移闸门仍然生效")
    void reconcileDrivenByRuleTableAndStillGated() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        // 制造漂移：子任务已送出，父订单仍停在已支付（模拟联动写入丢失）
        jdbcTemplate.update("UPDATE delivery_task SET status = 2 WHERE order_id = ?", orderId);

        // ===== 1. 只探测不执行：应命中「联动丢失补偿」，且不改变任何业务状态 =====
        List<ReconcileDecision> decisions = reconcileCoordinator.detect(StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        String hitRule = decisions.isEmpty() ? "（无）" : decisions.get(0).getRule().getName();
        String hitCondition = decisions.isEmpty() ? "（无）" : decisions.get(0).getRule().getChildCondition();
        int statusAfterDetect = orderStatus(orderId);

        // ===== 2. 执行补偿 =====
        List<ReconcileOutcome> outcomes = reconcileCoordinator.reconcile(StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusAfterReconcile = orderStatus(orderId);

        // ===== 3. 停用规则：同样的漂移不再被补偿（补偿由规则表驱动）=====
        jdbcTemplate.update("UPDATE order_info SET status = 2 WHERE id = ?", orderId);
        setReconcileRuleEnabled(RULE_LINK_LOST, false);
        int detectedWhenDisabled = reconcileCoordinator.detect(StateTransitions.SCENE_ORDER, BATCH_LIMIT).size();
        reconcileCoordinator.reconcile(StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusWhenDisabled = orderStatus(orderId);
        setReconcileRuleEnabled(RULE_LINK_LOST, true);
        int detectedWhenEnabled = reconcileCoordinator.detect(StateTransitions.SCENE_ORDER, BATCH_LIMIT).size();

        // ===== 4. 迁移闸门：规则仍命中，但闸门禁止该迁移时补偿不生效 =====
        //     （证明“补偿不是绕过可靠性层的旁路”，也说明配置化规则之间存在依赖）
        setTransitionAllowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_DELIVER,
                OrderStatus.PAID.getCode(), false);
        List<ReconcileOutcome> gated = reconcileCoordinator.reconcile(StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusWhenGated = orderStatus(orderId);
        long gatedRepaired = gated.stream().filter(ReconcileOutcome::isRepaired).count();
        setTransitionAllowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_DELIVER,
                OrderStatus.PAID.getCode(), true);

        // ===== 5. 闸门恢复后补偿成功 =====
        reconcileCoordinator.reconcile(StateTransitions.SCENE_ORDER, BATCH_LIMIT);
        int statusAfterGateRestored = orderStatus(orderId);

        report("实验六：补偿规则引擎",
                "探测命中规则", hitRule,
                "命中子过程条件", hitCondition,
                "只探测不执行的订单状态", statusAfterDetect + "（应仍为 2）",
                "执行补偿后订单状态", statusAfterReconcile,
                "停用规则后探测到的漂移数", detectedWhenDisabled,
                "停用规则并执行补偿后的订单状态", statusWhenDisabled + "（应仍为 2）",
                "恢复规则后探测到的漂移数", detectedWhenEnabled,
                "闸门禁止时本轮补偿生效数", gatedRepaired,
                "闸门禁止时的订单状态", statusWhenGated + "（应仍为 2）",
                "闸门恢复后订单状态", statusAfterGateRestored);

        assertThat(hitRule).isEqualTo(RULE_LINK_LOST);
        assertThat(hitCondition).isEqualTo("HAS_DISPATCHING_TASK");
        // 探测只读：状态不变
        assertThat(statusAfterDetect).isEqualTo(OrderStatus.PAID.getCode());
        // 补偿生效
        assertThat(statusAfterReconcile).isEqualTo(OrderStatus.DELIVERING.getCode());
        // 规则停用后：探测器不再命中，补偿也不再发生
        assertThat(detectedWhenDisabled).isZero();
        assertThat(statusWhenDisabled).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(detectedWhenEnabled).isEqualTo(1);
        // 迁移闸门禁止时：命中规则但补偿不生效（不绕过闸门）
        assertThat(gatedRepaired).isZero();
        assertThat(statusWhenGated).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(statusAfterGateRestored).isEqualTo(OrderStatus.DELIVERING.getCode());
    }
}
