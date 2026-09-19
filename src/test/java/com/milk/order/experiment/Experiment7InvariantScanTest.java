package com.milk.order.experiment;

import com.milk.order.common.enums.OrderStatus;
import com.milk.order.module.delivery.invariant.OrderTaskConsistencyInvariant;
import com.milk.order.module.delivery.invariant.SignIntakeConsistencyInvariant;
import com.milk.order.module.delivery.invariant.TaskRecordConsistencyInvariant;
import com.milk.order.module.order.invariant.OrderAggregationInvariant;
import com.milk.order.module.order.invariant.ParentTerminalChildPendingInvariant;
import com.milk.order.module.order.invariant.PaymentConsistencyInvariant;
import com.milk.order.module.product.invariant.QuotaLedgerInvariant;
import com.milk.order.process.invariant.InvariantScanReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验七：跨表不变量的运行期体检。
 *
 * <p>本实验要验证的主张是「<b>不变量只在被验证时才成立</b>」的反面：
 * 把跨表约束变成周期求值的检查项之后，不一致不再是“只有人翻出来才知道”，
 * 而是会被持续检出、可自动修的当场修掉、不可自动修的挂成人工待办。</p>
 *
 * <p>注入方式统一采用「直接改库」：不变量要防的本来就是绕过业务出口的旁路写入、进程异常
 * 与人工改库，用业务接口去改反而构造不出这些场景。</p>
 *
 * <p>四个对照：</p>
 * <ul>
 *   <li><b>对照 A</b>：进行中的订单注入 4 类违规 → 检出、自动修复、复检闭环；
 *       同时是**执行顺序依赖**的行为验证（父聚合必须排在网格补全之后）；</li>
 *   <li><b>对照 B</b>：父状态漂移自动修复 vs 资金不一致只告警——「哪些该自动修」的分界线；</li>
 *   <li><b>对照 C</b>：同一条不变量内部的等级细分——残余子任务「待配送」可安全自动作废，
 *       「配送中」（奶已出库在途）只能告警；</li>
 *   <li><b>对照 D</b>：终态订单的网格缺口只告警、**不补造历史**——因此不会再自己制造出组合异常。</li>
 * </ul>
 */
@DisplayName("实验七：跨表不变量的运行期体检")
class Experiment7InvariantScanTest extends ExperimentSupport {

    private static final int LIMIT = 500;

    @Test
    @DisplayName("对照A：进行中订单的四类违规——检出 → 自动修复 → 复检闭环")
    void autoRepairableInvariantsAreDetectedAndRepaired() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        LocalDate day3 = day1.plusDays(2);
        setQuota(day1, 100);
        long orderId = newPendingOrder(day1, day3, 1);
        orderInfoService.payOrder(orderId);
        // 前两日送出并签收，第三日留作待配送 → 订单处于「配送中」的进行中状态
        for (LocalDate date : List.of(day1, day2)) {
            deliveryTaskService.batchStartDelivery(date.toString(), null);
            signRecord(recordIdOf(taskIdOf(orderId, date)));
        }
        int statusBeforeInject = orderStatus(orderId);
        int healthyDetected = invariantScanner.scan(true, LIMIT).getTotalDetected();

        // ===== 注入四类违规（直接改库，模拟旁路写入 / 人工改库 / 进程异常）=====
        // INV_QUOTA_LEDGER：配额已售数与台账合计不一致（多算 3 盒）
        jdbcTemplate.update("UPDATE daily_quota SET used_quota = used_quota + 3 "
                + "WHERE quota_date = ? AND product_id = ?", day1, productId);
        // INV_TASK_RECORD：任务已完成，但签收记录被改回未签收
        jdbcTemplate.update("UPDATE delivery_record SET sign_status = 2 WHERE id = ?",
                recordIdOf(taskIdOf(orderId, day1)));
        // INV_SIGN_INTAKE：已签收，但营养摄入记录丢失
        jdbcTemplate.update("DELETE FROM nutrition_intake WHERE delivery_record_id = ?",
                recordIdOf(taskIdOf(orderId, day2)));
        // INV_ORDER_TASK：第三天的任务与签收记录整体丢失
        long lostTaskId = taskIdOf(orderId, day3);
        jdbcTemplate.update("DELETE FROM delivery_record WHERE task_id = ?", lostTaskId);
        jdbcTemplate.update("DELETE FROM delivery_task WHERE id = ?", lostTaskId);

        InvariantScanReport first = invariantScanner.scan(true, LIMIT);
        InvariantScanReport second = invariantScanner.scan(true, LIMIT);

        int statusAfterRepair = orderStatus(orderId);
        int tasksAfterRepair = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int intakesAfterRepair = count("SELECT COUNT(*) FROM nutrition_intake WHERE student_id = ?", studentId);
        int pendingAfterRepair = count("SELECT COUNT(*) FROM delivery_task "
                + "WHERE order_id = ? AND status = 1", orderId);

        report("实验七 · 对照A：进行中订单的四类可自动修复不变量",
                "健康状态下检出数（应 0）", healthyDetected,
                "注入前订单状态", statusBeforeInject + "（3=配送中）",
                "注入违规数", 4,
                "首轮检出总数", first.getTotalDetected(),
                "首轮自动修复数", first.getTotalRepaired(),
                "INV_QUOTA_LEDGER 检出/修复", stat(first, QuotaLedgerInvariant.CODE),
                "INV_TASK_RECORD 检出/修复", stat(first, TaskRecordConsistencyInvariant.CODE),
                "INV_SIGN_INTAKE 检出/修复", stat(first, SignIntakeConsistencyInvariant.CODE),
                "INV_ORDER_TASK 检出/修复", stat(first, OrderTaskConsistencyInvariant.CODE),
                "INV_PARENT_TERMINAL_CHILD_PENDING 检出（应 0，终态订单不再补造子任务）",
                detected(first, ParentTerminalChildPendingInvariant.CODE),
                "次轮检出总数（复检闭环）", second.getTotalDetected(),
                "修复后订单状态（应仍为 3，未被误判为已完成）", statusAfterRepair,
                "修复后任务数（应 3）", tasksAfterRepair,
                "修复后待配送任务数（应 1，即被补回的第三日）", pendingAfterRepair,
                "修复后营养记录数（应 2，第三日未签收故无摄入）", intakesAfterRepair);

        assertThat(healthyDetected).isZero();
        assertThat(statusBeforeInject).isEqualTo(OrderStatus.DELIVERING.getCode());

        // 四类不变量全部检出且全部自动修复
        assertThat(detected(first, QuotaLedgerInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, QuotaLedgerInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, TaskRecordConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, TaskRecordConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, SignIntakeConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, SignIntakeConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, OrderTaskConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, OrderTaskConsistencyInvariant.CODE)).isEqualTo(1);

        // ===== 执行顺序依赖的行为验证（§2.5 方案二）=====
        // 注入后、补网格前，订单的三个任务里有两个已是终态、第三个已被删除，
        // 因此若「父聚合」排在「网格补全」之前，同一次体检就会先把订单判成已完成（3→4），
        // 随后网格补全又补回一条待配送任务——自己制造出「已完成订单 + 待配送任务」。
        // 声明依赖后顺序为 网格补全 → 父聚合 → 组合守卫，两者都不会发生：
        assertThat(statusAfterRepair).isEqualTo(OrderStatus.DELIVERING.getCode());
        assertThat(detected(first, ParentTerminalChildPendingInvariant.CODE)).isZero();

        // 复检闭环 + 数据被真正修好（不是只把记录标记成已处理）
        assertThat(second.getTotalDetected()).isZero();
        assertThat(tasksAfterRepair).isEqualTo(3);
        assertThat(pendingAfterRepair).isEqualTo(1);
        assertThat(intakesAfterRepair).isEqualTo(2);
        assertThat(count("SELECT COALESCE(SUM(used_quota), 0) FROM daily_quota WHERE product_id = ?", productId))
                .isEqualTo(count("SELECT COALESCE(SUM(boxes), 0) FROM daily_quota_usage WHERE product_id = ?", productId));
    }

    @Test
    @DisplayName("对照B：父状态漂移自动补偿；资金不一致只告警、不自动修")
    void aggregationAutoRepairedButPaymentAlertOnly() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);

        // 漂移：子任务已送出，父订单仍为已支付
        jdbcTemplate.update("UPDATE delivery_task SET status = 2 WHERE order_id = ?", orderId);
        // 资金侧异常：订单仍是已支付，但成功流水被删除
        jdbcTemplate.update("DELETE FROM payment_record WHERE order_id = ?", orderId);

        InvariantScanReport first = invariantScanner.scan(true, LIMIT);
        int statusAfterFirst = orderStatus(orderId);
        InvariantScanReport second = invariantScanner.scan(true, LIMIT);

        Integer alertRecordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                Integer.class, PaymentConsistencyInvariant.CODE, orderId);
        String alertSeverity = jdbcTemplate.queryForObject(
                "SELECT severity FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, PaymentConsistencyInvariant.CODE, orderId);
        int paymentsLeft = count("SELECT COUNT(*) FROM payment_record WHERE order_id = ?", orderId);

        report("实验七 · 对照B：自动修复与仅告警的分界",
                "INV_ORDER_AGGREGATION 检出/修复", stat(first, OrderAggregationInvariant.CODE),
                "INV_PAYMENT_CONSISTENCY 检出/修复", stat(first, PaymentConsistencyInvariant.CODE),
                "补偿后订单状态", statusAfterFirst + "（3=配送中）",
                "次轮 INV_ORDER_AGGREGATION 检出", detected(second, OrderAggregationInvariant.CODE),
                "次轮 INV_PAYMENT_CONSISTENCY 检出", detected(second, PaymentConsistencyInvariant.CODE),
                "资金告警记录状态（0=未闭环）", alertRecordStatus,
                "资金告警记录等级", alertSeverity,
                "自动修复后剩余支付流水数（应 0）", paymentsLeft);

        // 父状态漂移：自动修复
        assertThat(detected(first, OrderAggregationInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, OrderAggregationInvariant.CODE)).isEqualTo(1);
        assertThat(statusAfterFirst).isEqualTo(OrderStatus.DELIVERING.getCode());

        // 资金不一致：被检出但不动数据，记录保持未闭环等人工
        assertThat(detected(first, PaymentConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, PaymentConsistencyInvariant.CODE)).isZero();
        assertThat(first.resultOf(PaymentConsistencyInvariant.CODE).getUnrepaired()).isZero();
        assertThat(alertRecordStatus).isZero();
        assertThat(alertSeverity).isEqualTo("ALERT_ONLY");

        // 次轮：自动修复项已闭环，资金告警仍挂着
        assertThat(detected(second, OrderAggregationInvariant.CODE)).isZero();
        assertThat(detected(second, PaymentConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(paymentsLeft).isZero();
    }

    @Test
    @DisplayName("对照C：终态父过程下的残余子任务——待配送自动作废，配送中只告警")
    void terminalParentWithLeftoverChildTask() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        long taskId = taskIdOf(orderId, date);
        signRecord(recordIdOf(taskId));
        int orderFinalStatus = orderStatus(orderId);

        // ===== 情形一：残余任务处于「配送中」 —— 奶已出库在途，只能告警 =====
        jdbcTemplate.update("UPDATE delivery_task SET status = 2 WHERE id = ?", taskId);
        InvariantScanReport deliveringScan = invariantScanner.scan(true, LIMIT);
        int taskStatusAfterDelivering = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        Integer alertRecordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                Integer.class, ParentTerminalChildPendingInvariant.CODE, taskId);
        String alertRecordSeverity = jdbcTemplate.queryForObject(
                "SELECT severity FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, ParentTerminalChildPendingInvariant.CODE, taskId);
        String alertDetail = jdbcTemplate.queryForObject(
                "SELECT detail FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, ParentTerminalChildPendingInvariant.CODE, taskId);

        // ===== 情形二：残余任务处于「待配送」 —— 奶未出库，可安全自动作废 =====
        jdbcTemplate.update("UPDATE delivery_task SET status = 1 WHERE id = ?", taskId);
        InvariantScanReport pendingScan = invariantScanner.scan(true, LIMIT);
        int taskStatusAfterPending = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int recordStatusAfterPending = count("SELECT sign_status FROM delivery_record WHERE task_id = ?", taskId);
        Integer closedRecordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                Integer.class, ParentTerminalChildPendingInvariant.CODE, taskId);
        String closedRecordSeverity = jdbcTemplate.queryForObject(
                "SELECT severity FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, ParentTerminalChildPendingInvariant.CODE, taskId);
        int cancelLedgerRows = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'delivery_task' AND entity_id = ? AND action = 'TASK_CANCEL' AND result = 1",
                taskId);

        report("实验七 · 对照C：终态父过程下残余子任务的分级处置",
                "订单终态", orderFinalStatus + "（4=已完成）",
                "情形一 · 残余任务状态（配送中）", 2,
                "情形一 · 守卫检出/修复", detected(deliveringScan, ParentTerminalChildPendingInvariant.CODE)
                        + " / " + repaired(deliveringScan, ParentTerminalChildPendingInvariant.CODE),
                "情形一 · 降级为仅告警条数", deliveringScan.resultOf(ParentTerminalChildPendingInvariant.CODE).getAlerts(),
                "情形一 · 体检后任务状态（应仍为 2，未被作废）", taskStatusAfterDelivering,
                "情形一 · 体检记录状态/等级", alertRecordStatus + " / " + alertRecordSeverity,
                "情形一 · 违规明细", alertDetail,
                "情形二 · 残余任务状态（待配送）", 1,
                "情形二 · 守卫检出/修复", detected(pendingScan, ParentTerminalChildPendingInvariant.CODE)
                        + " / " + repaired(pendingScan, ParentTerminalChildPendingInvariant.CODE),
                "情形二 · 体检后任务状态（应 4=已取消）", taskStatusAfterPending,
                "情形二 · 签收记录状态（已签收保持 1，不被作废联动覆盖）", recordStatusAfterPending,
                "情形二 · 体检记录状态/等级（应 1 已闭环/AUTO_REPAIR）",
                closedRecordStatus + " / " + closedRecordSeverity,
                "情形二 · 修复产生的 TASK_CANCEL 生效台账条数", cancelLedgerRows);

        assertThat(orderFinalStatus).isEqualTo(OrderStatus.COMPLETED.getCode());

        // 配送中的残余任务：检出但不动数据，记录保持未闭环、等级为仅告警
        assertThat(detected(deliveringScan, ParentTerminalChildPendingInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(deliveringScan, ParentTerminalChildPendingInvariant.CODE)).isZero();
        assertThat(deliveringScan.resultOf(ParentTerminalChildPendingInvariant.CODE).getAlerts()).isEqualTo(1);
        assertThat(taskStatusAfterDelivering).isEqualTo(2);
        assertThat(alertRecordStatus).isZero();
        assertThat(alertRecordSeverity).isEqualTo("ALERT_ONLY");
        assertThat(alertDetail).contains("已完成").contains("配送中");

        // 待配送的残余任务：自动作废，且修复经业务出口（任务→已取消、台账留痕）
        assertThat(detected(pendingScan, ParentTerminalChildPendingInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(pendingScan, ParentTerminalChildPendingInvariant.CODE)).isEqualTo(1);
        assertThat(taskStatusAfterPending).isEqualTo(4);
        // 本场景的记录已被签收过，而「作废联动只影响未签收记录」是有意设计（不覆盖既有签收结果），
        // 因此这里保持 1；若记录仍为未签收(2)，作废联动力会把它置为拒收(3)。
        assertThat(recordStatusAfterPending).isEqualTo(1);
        assertThat(closedRecordStatus).isEqualTo(1);
        assertThat(closedRecordSeverity).isEqualTo("AUTO_REPAIR");
        assertThat(cancelLedgerRows).isEqualTo(1);
    }

    @Test
    @DisplayName("对照D：终态订单的网格缺口只告警、不补造历史（因此不会自己制造组合异常）")
    void terminalOrderGridGapIsAlertOnlyAndNeverFabricatesHistory() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        long taskId = taskIdOf(orderId, date);
        signRecord(recordIdOf(taskId));
        int orderFinalStatus = orderStatus(orderId);

        // 注入：把已完成订单的整条任务与签收记录删掉（模拟历史被改过）
        jdbcTemplate.update("DELETE FROM nutrition_intake WHERE delivery_record_id = ?", recordIdOf(taskId));
        jdbcTemplate.update("DELETE FROM delivery_record WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM delivery_task WHERE id = ?", taskId);

        InvariantScanReport scan = invariantScanner.scan(true, LIMIT);
        int tasksAfter = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int statusAfter = orderStatus(orderId);
        Integer recordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                Integer.class, OrderTaskConsistencyInvariant.CODE, orderId);
        String recordSeverity = jdbcTemplate.queryForObject(
                "SELECT severity FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, OrderTaskConsistencyInvariant.CODE, orderId);
        String recordDetail = jdbcTemplate.queryForObject(
                "SELECT detail FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, OrderTaskConsistencyInvariant.CODE, orderId);

        report("实验七 · 对照D：终态订单网格缺口只告警不补造",
                "订单状态", orderFinalStatus + "（4=已完成）",
                "INV_ORDER_TASK 检出/修复", stat(scan, OrderTaskConsistencyInvariant.CODE),
                "降级为仅告警条数", scan.resultOf(OrderTaskConsistencyInvariant.CODE).getAlerts(),
                "体检后订单任务数（应 0：没有补造历史）", tasksAfter,
                "体检后订单状态（应仍为 4）", statusAfter,
                "INV_PARENT_TERMINAL_CHILD_PENDING 检出（应 0：未制造组合异常）",
                detected(scan, ParentTerminalChildPendingInvariant.CODE),
                "体检记录状态/等级（应 0 未闭环 / ALERT_ONLY）", recordStatus + " / " + recordSeverity,
                "违规明细", recordDetail);

        assertThat(orderFinalStatus).isEqualTo(OrderStatus.COMPLETED.getCode());
        // 检出但只告警：不补造历史
        assertThat(detected(scan, OrderTaskConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(scan, OrderTaskConsistencyInvariant.CODE)).isZero();
        assertThat(scan.resultOf(OrderTaskConsistencyInvariant.CODE).getAlerts()).isEqualTo(1);
        assertThat(tasksAfter).isZero();
        assertThat(statusAfter).isEqualTo(OrderStatus.COMPLETED.getCode());
        // 因此也不会出现「已完成订单 + 待配送任务」的组合异常
        assertThat(detected(scan, ParentTerminalChildPendingInvariant.CODE)).isZero();
        assertThat(recordStatus).isZero();
        assertThat(recordSeverity).isEqualTo("ALERT_ONLY");
        assertThat(recordDetail).contains("已完成").contains("缺失");
    }

    private int detected(InvariantScanReport report, String code) {
        return report.resultOf(code) == null ? -1 : report.resultOf(code).getDetected();
    }

    private int repaired(InvariantScanReport report, String code) {
        return report.resultOf(code) == null ? -1 : report.resultOf(code).getRepaired();
    }

    private String stat(InvariantScanReport report, String code) {
        return report.resultOf(code) == null ? "（未注册）"
                : report.resultOf(code).getDetected() + " / " + report.resultOf(code).getRepaired();
    }
}
