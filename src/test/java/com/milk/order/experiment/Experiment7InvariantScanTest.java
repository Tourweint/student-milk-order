package com.milk.order.experiment;

import com.milk.order.module.delivery.invariant.OrderTaskConsistencyInvariant;
import com.milk.order.module.delivery.invariant.SignIntakeConsistencyInvariant;
import com.milk.order.module.delivery.invariant.TaskRecordConsistencyInvariant;
import com.milk.order.module.order.invariant.OrderAggregationInvariant;
import com.milk.order.module.order.invariant.PaymentConsistencyInvariant;
import com.milk.order.module.product.invariant.QuotaLedgerInvariant;
import com.milk.order.common.enums.OrderStatus;
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
 */
@DisplayName("实验七：跨表不变量的运行期体检")
class Experiment7InvariantScanTest extends ExperimentSupport {

    private static final int LIMIT = 500;

    @Test
    @DisplayName("四类可自动修复的不变量：检出 → 自动修复 → 复检闭环")
    void autoRepairableInvariantsAreDetectedAndRepaired() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        LocalDate day3 = day1.plusDays(2);
        setQuota(day1, 100);
        long orderId = newPendingOrder(day1, day3, 1);
        orderInfoService.payOrder(orderId);

        // 三天全部送出并签收 → 任务/记录/营养齐备，订单自动完成
        for (LocalDate date : List.of(day1, day2, day3)) {
            deliveryTaskService.batchStartDelivery(date.toString(), null);
            signRecord(recordIdOf(taskIdOf(orderId, date)));
        }
        int statusAfterHappyPath = orderStatus(orderId);
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

        int tasksAfterRepair = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int intakesAfterRepair = count("SELECT COUNT(*) FROM nutrition_intake WHERE student_id = ?", studentId);
        int unfinishedAfterRepair = count("SELECT COUNT(*) FROM delivery_task "
                + "WHERE order_id = ? AND status IN (1, 2)", orderId);

        report("实验七 · 对照A：四类可自动修复的不变量",
                "健康状态下检出数（应 0）", healthyDetected,
                "正常路径结束的订单状态", statusAfterHappyPath + "（4=已完成）",
                "注入违规数", 4,
                "首轮检出总数", first.getTotalDetected(),
                "首轮自动修复数", first.getTotalRepaired(),
                "INV_QUOTA_LEDGER 检出/修复", stat(first, QuotaLedgerInvariant.CODE),
                "INV_TASK_RECORD 检出/修复", stat(first, TaskRecordConsistencyInvariant.CODE),
                "INV_SIGN_INTAKE 检出/修复", stat(first, SignIntakeConsistencyInvariant.CODE),
                "INV_ORDER_TASK 检出/修复", stat(first, OrderTaskConsistencyInvariant.CODE),
                "次轮检出总数（复检闭环）", second.getTotalDetected(),
                "修复后任务数（应 3）", tasksAfterRepair,
                "修复后营养记录数（应 3）", intakesAfterRepair,
                "修复后未完成任务数（组合异常观察项）", unfinishedAfterRepair);

        // 体检在健康数据上零误报
        assertThat(healthyDetected).isZero();
        assertThat(statusAfterHappyPath).isEqualTo(OrderStatus.COMPLETED.getCode());

        // 四类不变量全部检出且全部自动修复
        assertThat(detected(first, QuotaLedgerInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, QuotaLedgerInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, TaskRecordConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, TaskRecordConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, SignIntakeConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, SignIntakeConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(detected(first, OrderTaskConsistencyInvariant.CODE)).isEqualTo(1);
        assertThat(repaired(first, OrderTaskConsistencyInvariant.CODE)).isEqualTo(1);

        // 复检闭环：第二轮全部通过
        assertThat(second.getTotalDetected()).isZero();
        // 数据被真正修好，而不是只把记录标记成“已处理”
        assertThat(tasksAfterRepair).isEqualTo(3);
        assertThat(intakesAfterRepair).isEqualTo(3);
        assertThat(count("SELECT COALESCE(SUM(used_quota), 0) FROM daily_quota WHERE product_id = ?", productId))
                .isEqualTo(count("SELECT COALESCE(SUM(boxes), 0) FROM daily_quota_usage WHERE product_id = ?", productId));
    }

    @Test
    @DisplayName("父状态漂移被自动补偿；资金不一致只告警、不自动修")
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
