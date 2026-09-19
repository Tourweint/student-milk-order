package com.milk.order.experiment;

import com.milk.order.common.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验五（补充）：状态迁移留痕的完整性（台账不变量）。
 *
 * <p>过程层宣称「每一次状态迁移都写入台账」。这个不变量很容易被破坏——只要有一条路径
 * 绕过统一出口直接改状态，台账就会出现空洞，而「过程回放 / 问题回溯 / 对账取证」的可信度
 * 就随之失效。</p>
 *
 * <p>本用例走一条跨三张表、跨两级状态的完整链路（支付 → 退订联动），逐项核对：
 * 每一次状态变更都能在 `process_transition_log` 中找到对应记录，且最终状态与台账一致。</p>
 *
 * <p>回归背景（缺陷 D5）：退订/缺货取消联动作废签收记录时，原先由
 * {@code markRecordRejected} 绕过执行器直接更新，导致配送记录的 2→3 迁移无台账记录。</p>
 */
@DisplayName("实验五：状态迁移留痕完整性")
class Experiment5TransitionLedgerTest extends ExperimentSupport {

    @Test
    @DisplayName("支付 → 退订联动作废：订单/任务/签收记录的每一步迁移都有台账")
    void everyTransitionLeavesLedgerEntry() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 1000);
        long orderId = newPendingOrder(date, date, 2);

        // 支付：订单 待支付→已支付，展开 1 条任务 + 1 条签收记录，按品种扣 2 盒配额
        orderInfoService.payOrder(orderId);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAID.getCode());

        // 退订：订单 已支付→已退订；联动任务 待配送→已取消、签收记录 未签收→拒收；并按台账精确回补配额
        orderInfoService.cancelOrder(orderId, "实验五：退订联动作废");

        Integer finalOrderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_info WHERE id = ?", Integer.class, orderId);
        Integer finalTaskStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_task WHERE order_id = ?", Integer.class, orderId);
        Integer finalSignStatus = jdbcTemplate.queryForObject(
                "SELECT r.sign_status FROM delivery_record r JOIN delivery_task t ON r.task_id = t.id "
                        + "WHERE t.order_id = ?", Integer.class, orderId);

        int payTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int cancelTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'CANCEL' AND result = 1", orderId);
        int taskCancelTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'delivery_task' AND action = 'TASK_CANCEL' AND result = 1 "
                + "AND entity_id IN (SELECT id FROM delivery_task WHERE order_id = ?)", orderId);
        int recordRejectTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'delivery_record' AND action = 'REJECT' AND result = 1 "
                + "AND entity_id IN (SELECT r.id FROM delivery_record r "
                + "JOIN delivery_task t ON r.task_id = t.id WHERE t.order_id = ?)", orderId);

        int usedQuota = usedQuota(date);
        // 注意：回补后台账行做的是「逻辑删除」（DailyQuotaUsage 带 @TableLogic），
        // 因此业务视角不可见（deleted=0 计数为 0），但物理行仍存在（deleted=1）
        int activeUsageRows = count(
                "SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ? AND deleted = 0", orderId);
        int softDeletedUsageRows = count(
                "SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ? AND deleted = 1", orderId);

        report("实验五：状态迁移留痕完整性（支付 → 退订联动）",
                "订单最终状态（期望 5 已退订）", finalOrderStatus,
                "任务最终状态（期望 4 已取消）", finalTaskStatus,
                "签收记录最终状态（期望 3 拒收）", finalSignStatus,
                "台账 ORDER/PAY 生效条数（期望 1）", payTransitions,
                "台账 ORDER/CANCEL 生效条数（期望 1）", cancelTransitions,
                "台账 DELIVERY_TASK/TASK_CANCEL 条数（期望 1）", taskCancelTransitions,
                "台账 DELIVERY_RECORD/REJECT 条数（期望 1）", recordRejectTransitions,
                "配额剩余（期望回补到 0）", usedQuota,
                "有效扣减台账行数（期望 0）", activeUsageRows,
                "已逻辑删除的台账行数（保留审计痕迹）", softDeletedUsageRows);

        // 状态终值：订单已退订、任务已取消、签收记录拒收
        assertThat(finalOrderStatus).isEqualTo(OrderStatus.CANCELLED.getCode());
        assertThat(finalTaskStatus).isEqualTo(4);
        assertThat(finalSignStatus).isEqualTo(3);

        // 台账完整性：三张表的每一次迁移都有且仅有一条生效记录（缺陷 D5 的回归守卫）
        assertThat(payTransitions).isEqualTo(1);
        assertThat(cancelTransitions).isEqualTo(1);
        assertThat(taskCancelTransitions).isEqualTo(1);
        assertThat(recordRejectTransitions).isEqualTo(1);

        // 资源一致性：配额按台账精确回补；扣减台账行在业务视角已不可见
        assertThat(usedQuota).isZero();
        assertThat(activeUsageRows).isZero();
    }
}
