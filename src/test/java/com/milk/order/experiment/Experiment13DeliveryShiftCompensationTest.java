package com.milk.order.experiment;

import com.milk.order.module.delivery.vo.ShiftResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十三：配送日平移与拒收补送。
 *
 * <p>命题：</p>
 * <ol>
 *   <li><b>平移</b>：目标日已有同订单同品种任务时<b>合并 quantity</b>（而非"新建后撞唯一键被静默跳过"）；
 *       周期外则新建；重复执行因 CAS 幂等不重复加量；源任务配送中时拒绝。</li>
 *   <li><b>零散订单拒收</b>：次日无任务 → 新建补送任务 + 追加次日机动配额；
 *       且补送落库先于完成判定，订单不会被误判完成（否则补送任务会被组合守卫自动作废）。</li>
 *   <li><b>套餐订单拒收</b>：次日已有任务 → 合并 quantity；套餐不占机动配额，不得写配额台账。</li>
 *   <li><b>期末摊平</b>：重置基准 = 1 + 补送量（不抹掉补送）；窗口外待配送任务作废；总量守恒；重复执行幂等。</li>
 * </ol>
 */
@DisplayName("实验十三：配送日平移与拒收补送")
class Experiment13DeliveryShiftCompensationTest extends ExperimentSupport {

    @Test
    @DisplayName("平移：合并到目标日、周期外新建、重复执行幂等、配送中拒绝")
    void shiftTasksMergeOrCreateAndAreIdempotent() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        LocalDate day3 = day2.plusDays(1);
        LocalDate day4 = day3.plusDays(1);
        long orderId = newPaidOrder(day1, day3, 1, 1L); // 套餐：周期 3 天，各 1 盒
        long task1 = taskIdOf(orderId, day1);
        long task3 = taskIdOf(orderId, day3);

        // ① 合并：day1 → day2（day2 已有同订单同品种任务）
        ShiftResultVO merged = deliveryTaskService.shiftTasksToDate(List.of(task1), day2.toString());
        int day1Status = taskStatus(orderId, day1);
        int day2QtyAfterMerge = taskQuantity(orderId, day2);
        int originalRecordStatus = count("SELECT sign_status FROM delivery_record WHERE task_id = ?", task1);

        // ② 幂等：同参数重试（CAS 应挡住，不重复加量）
        ShiftResultVO retry = deliveryTaskService.shiftTasksToDate(List.of(task1), day2.toString());
        int day2QtyAfterRetry = taskQuantity(orderId, day2);

        // ③ 周期外新建：day3 → day4
        ShiftResultVO created = deliveryTaskService.shiftTasksToDate(List.of(task3), day4.toString());
        int day4Status = taskStatus(orderId, day4);
        int day4Qty = taskQuantity(orderId, day4);

        // ④ 预检：源任务配送中 → 拒绝整批
        deliveryTaskService.batchStartDelivery(day2.toString(), null);
        long day2TaskId = taskIdOf(orderId, day2);
        String precheckError = null;
        try {
            deliveryTaskService.shiftTasksToDate(List.of(day2TaskId), day1.toString());
        } catch (Exception e) {
            precheckError = e.getClass().getSimpleName();
        }

        report("实验十三 · 平移（合并 / 新建 / 幂等 / 预检）",
                "首次 shifted/merged/created/skipped",
                merged.getShifted() + " / " + merged.getMerged() + " / " + merged.getCreated() + " / " + merged.getSkipped(),
                "day1 任务状态（期望 4 已取消）", day1Status,
                "day2 数量（期望 2 合并）", day2QtyAfterMerge,
                "原记录签收状态（期望 2 未签收，未被置拒收）", originalRecordStatus,
                "重试 shifted/skipped（期望 0 / 1）", retry.getShifted() + " / " + retry.getSkipped(),
                "重试后 day2 数量（期望仍 2）", day2QtyAfterRetry,
                "新建 shifted/created（期望 1 / 1）", created.getShifted() + " / " + created.getCreated(),
                "day4 状态/数量（期望 1 / 1）", day4Status + " / " + day4Qty,
                "配送中任务平移（期望 BusinessException）", precheckError);

        assertThat(merged.getShifted()).isEqualTo(1);
        assertThat(merged.getMerged()).isEqualTo(1);
        assertThat(day1Status).isEqualTo(4);
        assertThat(day2QtyAfterMerge).isEqualTo(2);
        assertThat(originalRecordStatus).isEqualTo(2);
        assertThat(retry.getShifted()).isZero();
        assertThat(retry.getSkipped()).isEqualTo(1);
        assertThat(day2QtyAfterRetry).isEqualTo(2);
        assertThat(created.getCreated()).isEqualTo(1);
        assertThat(day4Status).isEqualTo(1);
        assertThat(day4Qty).isEqualTo(1);
        assertThat(precheckError).isEqualTo("BusinessException");
    }

    @Test
    @DisplayName("零散订单拒收：次日新建补送任务 + 追加次日配额 + 订单不被误完成")
    void scatteredOrderRejectCreatesCompensationTask() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate next = day1.plusDays(1);
        long orderId = newPaidOrder(day1, day1, 1, null); // 零散：单日
        setQuota(next, 10);                               // 补送日机动池
        deliveryTaskService.batchStartDelivery(day1.toString(), null);
        long sourceTask = taskIdOf(orderId, day1);
        rejectRecord(recordIdOf(sourceTask), "SOUR", "变质异味");

        String reasonCode = jdbcTemplate.queryForObject(
                "SELECT reject_reason_code FROM delivery_record WHERE task_id = ?", String.class, sourceTask);

        report("实验十三 · 零散订单拒收补送",
                "补偿台账条数（期望 1）", compensationCount(sourceTask),
                "原任务状态（期望 4）", taskStatus(orderId, day1),
                "次日任务 数量/状态（期望 1 / 1 新建）",
                taskQuantity(orderId, next) + " / " + taskStatus(orderId, next),
                "次日配额已售（期望 1）", usedQuota(next),
                "次日台账行数（期望 1）", count("SELECT COUNT(*) FROM daily_quota_usage WHERE quota_date = ?", next),
                "拒收原因分类（期望 SOUR）", reasonCode,
                "订单状态（期望 3 配送中，未被误判完成）", orderStatus(orderId));

        assertThat(compensationCount(sourceTask)).isEqualTo(1);
        assertThat(taskStatus(orderId, day1)).isEqualTo(4);
        assertThat(taskQuantity(orderId, next)).isEqualTo(1);
        assertThat(taskStatus(orderId, next)).isEqualTo(1);
        assertThat(usedQuota(next)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM daily_quota_usage WHERE quota_date = ?", next)).isEqualTo(1);
        assertThat(reasonCode).isEqualTo("SOUR");
        assertThat(orderStatus(orderId)).isEqualTo(3);
    }

    @Test
    @DisplayName("套餐订单拒收：合并到次日任务，不动机动配额")
    void packageOrderRejectMergesQuantityWithoutQuota() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        long orderId = newPaidOrder(day1, day2, 1, 1L); // 套餐：2 天
        deliveryTaskService.batchStartDelivery(day1.toString(), null);
        long sourceTask = taskIdOf(orderId, day1);
        rejectRecord(recordIdOf(sourceTask), "DAMAGED", null);

        long day2TaskId = taskIdOf(orderId, day2);
        int day2Qty = taskQuantity(orderId, day2);
        int day2RecordQty = count("SELECT quantity FROM delivery_record WHERE task_id = ?", day2TaskId);
        int quotaLedgerRows = count("SELECT COUNT(*) FROM daily_quota_usage");

        report("实验十三 · 套餐订单拒收补送",
                "补偿台账条数（期望 1）", compensationCount(sourceTask),
                "次日任务数量（期望 2 合并）", day2Qty,
                "次日签收记录数量（期望 2，签收按 record.quantity 算营养）", day2RecordQty,
                "配额台账行数（期望 0，套餐不占配额）", quotaLedgerRows,
                "订单状态（期望 3）", orderStatus(orderId));

        assertThat(compensationCount(sourceTask)).isEqualTo(1);
        assertThat(day2Qty).isEqualTo(2);
        assertThat(day2RecordQty).isEqualTo(2);
        assertThat(quotaLedgerRows).isZero();
        assertThat(orderStatus(orderId)).isEqualTo(3);
    }

    @Test
    @DisplayName("期末摊平：保留补送量、尾部作废、总量守恒、重复执行幂等")
    void rebalanceKeepsCompensationAndCancelsTail() {
        LocalDate d1 = LocalDate.now().plusDays(1);
        LocalDate d2 = d1.plusDays(1);
        LocalDate d5 = d1.plusDays(4);
        LocalDate d6 = d1.plusDays(5);
        long orderId = newPaidOrder(d1, d6, 1, 1L); // 套餐：6 天

        // 造"已有一次补送"：d2 任务数量为 2，并写一条补偿台账（source 用 d6 任务充当）
        long t2 = taskIdOf(orderId, d2);
        jdbcTemplate.update("UPDATE delivery_task SET quantity = 2 WHERE id = ?", t2);
        jdbcTemplate.update("INSERT INTO delivery_compensation (source_task_id, target_task_id, order_id, product_id, "
                        + "boxes, compensation_date, remark, deleted) VALUES (?, ?, ?, ?, 1, ?, ?, 0)",
                taskIdOf(orderId, d6), t2, orderId, productId, d2, TAG);

        LocalDate deadline = d1.plusDays(3);
        int adjusted = deliveryTaskService.adjustQuantitiesBeforeDeadline(orderId, deadline.toString());
        int windowTotal = count(
                "SELECT COALESCE(SUM(quantity), 0) FROM delivery_task WHERE order_id = ? AND status = 1", orderId);
        int d2Qty = taskQuantity(orderId, d2);
        int d5Status = taskStatus(orderId, d5);
        int d6Status = taskStatus(orderId, d6);
        int secondRun = deliveryTaskService.adjustQuantitiesBeforeDeadline(orderId, deadline.toString());

        report("实验十三 · 期末摊平",
                "首次调整条数", adjusted,
                "d2 数量（期望 3：摊平计划 2 + 补送 1，补送未被抹掉）", d2Qty,
                "窗口内待配送总量（期望 7：与摊平前守恒）", windowTotal,
                "d5 状态（期望 4 作废）", d5Status,
                "d6 状态（期望 4 作废）", d6Status,
                "重复执行调整条数（期望 0 幂等）", secondRun);

        assertThat(adjusted).isPositive();
        assertThat(d2Qty).isEqualTo(3);
        assertThat(windowTotal).isEqualTo(7);
        assertThat(d5Status).isEqualTo(4);
        assertThat(d6Status).isEqualTo(4);
        assertThat(secondRun).isZero();
    }
}
