package com.milk.order.contract;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.experiment.ExperimentSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机契约矩阵测试（不是实验，而是回归守卫）。
 *
 * <p>它不是验证「某个场景能被修好」，而是把状态机的三条结构性约束钉成可执行断言，
 * 让它们在后续任何改动中都不可能被悄悄破坏：</p>
 * <ol>
 *   <li><b>矩阵 A · 规则覆盖</b>：白名单语义下，漏配一行 = 该迁移静默被禁止（且没有报错），
 *       因此每个场景/动作必须覆盖全部可达来源状态；</li>
 *   <li><b>矩阵 B · 安全关键禁止</b>：已完成任务不可回退、已退订订单不可再流转、配送中订单不可退订等，
 *       这些是业务安全底线，必须显式禁止；未配置的组合同样必须默认禁止；</li>
 *   <li><b>矩阵 C · 台账可回放</b>：迁移台账必须完整且首尾相接，
 *       用它重放一遍就能得到与真实状态一致的结果——这是「过程可观测」的最低要求。</li>
 * </ol>
 */
@DisplayName("契约矩阵测试：状态机规则覆盖 / 迁移闸门 / 台账可回放")
class StateMachineContractTest extends ExperimentSupport {

    /** 订单状态机：动作 → 必须配置规则的来源状态集合（可达即需配置） */
    private static final Map<String, List<Integer>> ORDER_ACTIONS = new LinkedHashMap<>();

    /** 配送任务状态机：动作 → 必须配置规则的来源状态集合 */
    private static final Map<String, List<Integer>> TASK_ACTIONS = new LinkedHashMap<>();

    /** 退款单状态机：动作 → 必须配置规则的来源状态集合（1待审核 2已审核待退款 3已退款 4已拒绝 5已取消） */
    private static final Map<String, List<Integer>> REFUND_ACTIONS = new LinkedHashMap<>();

    static {
        ORDER_ACTIONS.put(StateTransitions.ACTION_PAY, List.of(1, 2, 3, 4, 5));
        ORDER_ACTIONS.put(StateTransitions.ACTION_CANCEL, List.of(1, 2, 3, 4, 5));
        ORDER_ACTIONS.put(StateTransitions.ACTION_DELIVER, List.of(1, 2, 3, 4, 5));
        ORDER_ACTIONS.put(StateTransitions.ACTION_AUTO_COMPLETE, List.of(2, 3));
        ORDER_ACTIONS.put(StateTransitions.ACTION_COMPLETE, List.of(2, 3, 4));

        TASK_ACTIONS.put(StateTransitions.ACTION_DISPATCH, List.of(1, 2, 3, 4));
        TASK_ACTIONS.put(StateTransitions.ACTION_TASK_CANCEL, List.of(1, 2, 3, 4));
        TASK_ACTIONS.put(StateTransitions.ACTION_SIGN, List.of(2, 3));
        TASK_ACTIONS.put(StateTransitions.ACTION_REJECT, List.of(2, 3));
        TASK_ACTIONS.put(StateTransitions.ACTION_STOCKOUT_CANCEL, List.of(1, 2, 3));

        // 退款单：审核/拒绝/执行三个动作都必须覆盖全部可达来源状态（含显式 allowed=0 的封闭行）
        REFUND_ACTIONS.put(StateTransitions.ACTION_AUDIT, List.of(1, 2, 3, 4, 5));
        REFUND_ACTIONS.put(StateTransitions.ACTION_REJECT, List.of(1, 2, 3, 4, 5));
        REFUND_ACTIONS.put(StateTransitions.ACTION_EXECUTE, List.of(1, 2, 3, 4, 5));
    }

    @Test
    @DisplayName("矩阵A：每个场景/动作都覆盖了全部可达来源状态（漏配 = 静默禁止）")
    void ruleTableCoversAllReachableFromStatuses() {
        List<String> missing = new ArrayList<>();
        collectMissing(StateTransitions.SCENE_ORDER, ORDER_ACTIONS, missing);
        collectMissing(StateTransitions.SCENE_DELIVERY_TASK, TASK_ACTIONS, missing);
        collectMissing(StateTransitions.SCENE_REFUND, REFUND_ACTIONS, missing);

        report("契约矩阵 · A：规则覆盖完整性",
                "订单动作数", ORDER_ACTIONS.size(),
                "任务动作数", TASK_ACTIONS.size(),
                "退款单动作数", REFUND_ACTIONS.size(),
                "应覆盖的（场景/动作/来源状态）组合数", totalCombinations(),
                "实际缺失组合数", missing.size(),
                "缺失明细", missing.isEmpty() ? "（无）" : missing);

        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("矩阵B：安全关键迁移必须被禁止，未配置组合默认禁止")
    void safetyCriticalTransitionsAreForbidden() {
        // 已完成/已退订的订单：不可支付、不可退订、不可开始配送、不可再完成（禁止状态回退）
        assertThat(allowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_PAY, 5)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_CANCEL, 5)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_DELIVER, 4)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_COMPLETE, 4)).isFalse();
        // 退款闸门：配送中订单禁止退订（奶已送出）
        assertThat(allowed(StateTransitions.SCENE_ORDER, StateTransitions.ACTION_CANCEL, 3)).isFalse();
        // 已完成任务：禁止回退（重复签收/取消/重送/改为拒收）
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_SIGN, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_TASK_CANCEL, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_DISPATCH, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_REJECT, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_STOCKOUT_CANCEL, 3)).isFalse();
        // 已取消任务：禁止任何回退
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_SIGN, 4)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_DISPATCH, 4)).isFalse();
        // 退款单安全底线：未审核不得直接执行；已退款/已拒绝/已取消不得再审核或重复执行
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_EXECUTE, 1)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_EXECUTE, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_AUDIT, 3)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_AUDIT, 4)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_EXECUTE, 4)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_REFUND, StateTransitions.ACTION_EXECUTE, 5)).isFalse();
        // 白名单语义：未配置的组合默认禁止
        assertThat(allowed(StateTransitions.SCENE_ORDER, "UNKNOWN_ACTION", 1)).isFalse();
        assertThat(allowed(StateTransitions.SCENE_DELIVERY_TASK, StateTransitions.ACTION_SIGN, 1)).isFalse();
    }

    @Test
    @DisplayName("矩阵C：迁移台账完整且首尾相接，可用于回放订单的状态变化")
    void ledgerReplaysOrderLifecycle() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);

        // 走完一条完整生命周期：待支付 → 已支付 → 配送中 → 已完成
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        signRecord(recordIdOf(taskIdOf(orderId, date)));

        List<int[]> transitions = jdbcTemplate.query(
                "SELECT from_status, to_status FROM process_transition_log "
                        + "WHERE entity_type = 'order_info' AND entity_id = ? AND result = 1 ORDER BY id",
                (rs, rowNum) -> new int[]{rs.getInt("from_status"), rs.getInt("to_status")},
                orderId);

        int replayed = OrderStatus.PENDING_PAYMENT.getCode();
        List<String> replayPath = new ArrayList<>();
        replayPath.add(String.valueOf(replayed));
        for (int[] transition : transitions) {
            // 回放一致性：每条台账的“迁移前状态”必须等于回放到此刻的状态（无缝隙、无跳跃）
            assertThat(transition[0]).as("台账必须首尾相接").isEqualTo(replayed);
            replayed = transition[1];
            replayPath.add(String.valueOf(replayed));
        }

        report("契约矩阵 · C：台账可回放",
                "台账条数", transitions.size(),
                "回放路径", String.join(" → ", replayPath),
                "回放得到的状态", replayed,
                "订单实际状态", orderStatus(orderId));

        assertThat(transitions).hasSize(3);
        assertThat(replayed).isEqualTo(orderStatus(orderId));
        assertThat(replayed).isEqualTo(OrderStatus.COMPLETED.getCode());
    }

    private void collectMissing(String scene, Map<String, List<Integer>> actions, List<String> missing) {
        actions.forEach((action, statuses) -> statuses.forEach(fromStatus -> {
            int rows = count("SELECT COUNT(*) FROM state_transition_rule "
                    + "WHERE scene = ? AND action = ? AND from_status = ?", scene, action, fromStatus);
            if (rows == 0) {
                missing.add(scene + "/" + action + "/" + fromStatus);
            }
        }));
    }

    private int totalCombinations() {
        return ORDER_ACTIONS.values().stream().mapToInt(List::size).sum()
                + TASK_ACTIONS.values().stream().mapToInt(List::size).sum()
                + REFUND_ACTIONS.values().stream().mapToInt(List::size).sum();
    }

    private boolean allowed(String scene, String action, int fromStatus) {
        return stateMachineService.allowed(scene, action, fromStatus);
    }
}
