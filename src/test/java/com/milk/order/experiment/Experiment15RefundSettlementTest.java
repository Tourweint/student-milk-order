package com.milk.order.experiment;

import com.milk.order.module.delivery.dto.StockoutCancelRequest;
import com.milk.order.module.refund.dto.RefundApplyRequest;
import com.milk.order.module.refund.dto.RefundAuditRequest;
import com.milk.order.module.refund.entity.RefundOrder;
import com.milk.order.module.refund.service.RefundOrderService;
import com.milk.order.module.refund.vo.RefundPreviewVO;
import com.milk.order.module.refund.vo.SettlementResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十五：退款域（退款单父过程）与毕业清算。
 *
 * <p>命题（对应《退款与毕业清算设计方案》§9 实验十五）：
 * ① 并发申请幂等（同订单 N 线程申请 → 1 张进行中退款单）；
 * ② 重复执行退款只落账一次（任务只作废一次、台账只记一次）；
 * ③ 部分配送后退款金额守恒（含尾差按累计制自找平）；
 * ④ 退款执行与配送并发竞争：金额按实际作废成功的盒数结算，"退了钱又送奶"不存在；
 * ⑤ 缺货取消期次可退（与 INV_TASK_COMPENSATION 同判据）且配额不重复回补；
 * ⑥ 毕业清算幂等（含待支付单的混合清算）。</p>
 *
 * <p>实验全程只写独立实验库；定时任务关闭，父订单聚合由实验显式驱动实时通道（drainPendingTasks），
 * 与 {@code ProcessPendingTaskJob} 的循环体是同一组调用。</p>
 */
@DisplayName("实验十五：退款与毕业清算")
class Experiment15RefundSettlementTest extends ExperimentSupport {

    /** 退款单状态：已退款 */
    private static final int REFUND_REFUNDED = 3;
    /** 退款单状态：已拒绝 */
    private static final int REFUND_REJECTED = 4;

    @Autowired
    private RefundOrderService refundOrderService;

    @Test
    @DisplayName("并发申请：同订单 N 线程申请只落一张进行中退款单（uk_refund_active 仲裁）")
    void concurrentApplyKeepsSingleActiveRefund() throws InterruptedException {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(9), 1, 1L); // 10 天 × 1 盒，套餐

        ConcurrentOutcome outcome = runConcurrently(8, index -> {
            refundOrderService.applyRefund(orderId, applyRequest(10, "并发申请实验"));
            return true;
        });

        int total = count("SELECT COUNT(*) FROM refund_order WHERE order_id = ?", orderId);
        int active = count("SELECT COUNT(*) FROM refund_order WHERE order_id = ? AND status IN (1, 2)", orderId);

        report("实验十五 · 并发申请幂等",
                "并发线程数", 8,
                "申请成功数（期望 1）", outcome.success,
                "申请失败数（期望 7）", outcome.failed,
                "该订单退款单总数（期望 1）", total,
                "其中进行中（期望 1）", active,
                "失败原因摘要", outcome.errorSummary());

        assertThat(outcome.success).isEqualTo(1);
        assertThat(total).isEqualTo(1);
        assertThat(active).isEqualTo(1);
    }

    @Test
    @DisplayName("并发执行：任务只作废一次、金额只落账一次、台账只记一次，父订单由实时通道收敛")
    void concurrentExecuteRefundsExactlyOnce() throws InterruptedException {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(4), 1, 1L); // 5 天 × 1 盒 → 实付 15.00
        Long refundId = applyAndAudit(orderId, 5);

        ConcurrentOutcome outcome = runConcurrently(6, index -> {
            refundOrderService.executeRefund(refundId);
            return true;
        });

        RefundOrder refund = refundOrderService.getById(refundId);
        int refundedRows = count("SELECT COUNT(*) FROM refund_order WHERE order_id = ? AND status = 3", orderId);
        int cancelledTasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ? AND status = 4", orderId);
        int pendingTasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ? AND status = 1", orderId);
        int ledgerRows = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'refund_order' AND entity_id = ? AND action = 'EXECUTE' AND result = 1", refundId);

        report("实验十五 · 并发执行退款",
                "并发线程数", 6,
                "执行成功数（期望 1）", outcome.success,
                "执行失败数（期望 5）", outcome.failed,
                "已退款单数（期望 1）", refundedRows,
                "退款单状态（期望 3）", refund.getStatus(),
                "退款盒数 / 金额（期望 5 / 15.00）", refund.getRefundedBoxes() + " / " + refund.getRefundAmount(),
                "已作废任务数（期望 5）", cancelledTasks,
                "仍待配送任务数（期望 0）", pendingTasks,
                "退款执行台账条数（期望 1）", ledgerRows);

        assertThat(outcome.success).isEqualTo(1);
        assertThat(refund.getStatus()).isEqualTo(REFUND_REFUNDED);
        assertThat(refundedRows).isEqualTo(1);
        assertThat(refund.getRefundedBoxes()).isEqualTo(5);
        assertThat(refund.getRefundAmount()).isEqualByComparingTo("15.00");
        assertThat(cancelledTasks).isEqualTo(5);
        assertThat(pendingTasks).isZero();
        assertThat(ledgerRows).isEqualTo(1);

        // 退款侧只入队待办、不直连 OrderInfoService（避免 order ↔ refund 依赖环）：
        // 父订单状态由实时通道按 process_reconcile_rule 收敛
        drainPendingTasks(50);
        int statusAfterConverge = orderStatus(orderId);
        report("实验十五 · 退款后父订单收敛（实时通道）",
                "订单状态（期望 4 已完成）", statusAfterConverge);
        assertThat(statusAfterConverge).isEqualTo(4);
    }

    @Test
    @DisplayName("部分配送后退款：金额 = 实付 × 可退盒数 / 合同总盒数，尾差按累计制自找平")
    void partialDeliveryRefundConservesAmount() {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(2), 1, 1L); // 3 天 × 1 盒
        // 让实付变成 10.00（10/3 除不尽），专门检验尾差：逐笔四舍五入会漂移，累计制不会
        jdbcTemplate.update("UPDATE order_info SET pay_amount = 10.00 WHERE id = ?", orderId);

        long firstTaskId = taskIdOf(orderId, start);
        deliveryTaskService.startDelivery(firstTaskId);
        signRecord(recordIdOf(firstTaskId));

        RefundPreviewVO preview = refundOrderService.preview(orderId);
        Long refundId = applyAndAudit(orderId, 2);
        refundOrderService.executeRefund(refundId);
        RefundOrder refund = refundOrderService.getById(refundId);

        BigDecimal deliveredShare = new BigDecimal("10.00")
                .multiply(BigDecimal.valueOf(1))
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP); // 3.33

        report("实验十五 · 部分配送后退款金额守恒",
                "预览可退盒数 / 预估金额（期望 2 / 6.67）",
                preview.getRefundableBoxes() + " / " + preview.getEstimatedAmount(),
                "实际退款盒数（期望 2）", refund.getRefundedBoxes(),
                "实际退款金额（期望 6.67）", refund.getRefundAmount(),
                "已签收期次价值（10 / 3 四舍五入）", deliveredShare,
                "已退款 + 已签收价值（期望 10.00，尾差自找平）", refund.getRefundAmount().add(deliveredShare),
                "已签收任务状态（期望 3 不受退款影响）", taskStatus(orderId, start));

        // 预览与实际执行共用同一计算方法（R7）
        assertThat(preview.getRefundableBoxes()).isEqualTo(2);
        assertThat(preview.getEstimatedAmount()).isEqualByComparingTo("6.67");
        assertThat(refund.getRefundedBoxes()).isEqualTo(2);
        assertThat(refund.getRefundAmount()).isEqualByComparingTo("6.67");
        // 守恒：退款金额 + 已配送期次价值 = 实付（10 × 2/3 + 10 × 1/3 = 10.00）
        assertThat(refund.getRefundAmount().add(deliveredShare)).isEqualByComparingTo("10.00");
        // 已签收期次不可退：仍保持已完成
        assertThat(taskStatus(orderId, start)).isEqualTo(3);
    }

    @Test
    @DisplayName("退款与配送并发：金额只按实际作废成功的盒数结算，作废盒数 + 已送出盒数 = 合同总盒数")
    void refundRacesWithDeliveryAndChargesActualCancelledBoxes() throws InterruptedException {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(1), 2, 1L); // 2 天 × 2 盒 = 4 盒 → 实付 12.00
        Long refundId = applyAndAudit(orderId, 4);
        long firstTaskId = taskIdOf(orderId, start);

        ConcurrentOutcome outcome = runConcurrently(2, index -> {
            if (index == 0) {
                refundOrderService.executeRefund(refundId);
            } else {
                deliveryTaskService.startDelivery(firstTaskId);
            }
            return true;
        });

        RefundOrder refund = refundOrderService.getById(refundId);
        int deliveredBoxes = count("SELECT COALESCE(SUM(quantity), 0) FROM delivery_task "
                + "WHERE order_id = ? AND status IN (2, 3)", orderId);
        int deliveredTasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ? AND status IN (2, 3)", orderId);
        int cancelledTasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ? AND status = 4", orderId);
        BigDecimal expected = new BigDecimal("12.00")
                .multiply(BigDecimal.valueOf(refund.getRefundedBoxes()))
                .divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);

        report("实验十五 · 退款与配送并发竞争",
                "成功数 / 失败数（谁先谁后由并发决定）", outcome.success + " / " + outcome.failed,
                "退款盒数（实际作废成功）", refund.getRefundedBoxes(),
                "退款金额（期望 = 12.00 × 作废盒数 / 4）", refund.getRefundAmount() + "（期望 " + expected + "）",
                "已送出盒数（配送中 + 已完成）", deliveredBoxes,
                "作废盒数 + 已送出盒数（期望 4 守恒）", (refund.getRefundedBoxes() + deliveredBoxes),
                "任务归属：已送出任务数 / 已作废任务数", deliveredTasks + " / " + cancelledTasks);

        // 守恒：钱按实际作废成功的盒数结算，未抢到的期次照常配送、不计价
        assertThat(refund.getRefundedBoxes() + deliveredBoxes).isEqualTo(4);
        assertThat(refund.getRefundAmount()).isEqualByComparingTo(expected);
        // 每条任务只能落在"已送出"或"退款作废"之一，不存在"退了钱又送奶"
        assertThat(deliveredTasks + cancelledTasks).isEqualTo(2);
        assertThat(refund.getRefundAmount()).isLessThanOrEqualTo(new BigDecimal("12.00"));
    }

    @Test
    @DisplayName("缺货取消期次可退（同 INV_TASK_COMPENSATION 判据），配额不重复回补，已完成订单仍可退")
    void stockoutCancelledTaskIsRefundableWithoutRestoringQuotaTwice() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 10);
        long orderId = newPendingOrder(date, date, 2);
        orderInfoService.payOrder(orderId);
        int usedAfterPay = usedQuota(date);

        StockoutCancelRequest stockout = new StockoutCancelRequest();
        stockout.setDeliveryDate(date.toString());
        stockout.setProductId(productId);
        stockout.setReason("实验缺货取消");
        deliveryTaskService.stockoutCancel(stockout);
        int usedAfterStockout = usedQuota(date);
        int statusAfterStockout = orderStatus(orderId);

        Long refundId = applyAndAudit(orderId, 2);
        refundOrderService.executeRefund(refundId);
        RefundOrder refund = refundOrderService.getById(refundId);
        int usedAfterRefund = usedQuota(date);

        report("实验十五 · 缺货取消期次退款",
                "支付后占用配额（期望 2）", usedAfterPay,
                "缺货取消后占用配额（期望 0 已回补）", usedAfterStockout,
                "缺货取消后订单状态（期望 4 全部任务终态已聚合）", statusAfterStockout,
                "退款盒数（期望 2 可退）", refund.getRefundedBoxes(),
                "退款金额（零散按明细单价 3.00 × 2 精确计价）", refund.getRefundAmount(),
                "退款后占用配额（期望 0，不重复回补也不减成负数）", usedAfterRefund);

        assertThat(usedAfterPay).isEqualTo(2);
        assertThat(usedAfterStockout).isZero();
        // 订单因"任务全部终态"被聚合为已完成(4)，但缺货取消的期次没送奶 → 仍必须可退（R1/R7 边界）
        assertThat(statusAfterStockout).isEqualTo(4);
        assertThat(refund.getRefundedBoxes()).isEqualTo(2);
        assertThat(refund.getRefundAmount()).isEqualByComparingTo("6.00");
        assertThat(usedAfterRefund).isZero();
    }

    @Test
    @DisplayName("毕业清算：待支付单取消 + 已支付单退款，重复执行无可清算订单（幂等）")
    void settlementIsIdempotentForMixedOrders() {
        LocalDate date = LocalDate.now().plusDays(1);
        long unpaidOrderId = newPendingOrder(date, date, 1);
        long paidOrderId = newPaidOrder(date, date.plusDays(2), 1, 1L); // 3 天 × 1 盒 → 实付 9.00

        SettlementResultVO first = refundOrderService.settleStudent(studentId);
        drainPendingTasks(50);
        SettlementResultVO second = refundOrderService.settleStudent(studentId);

        int refundRows = count("SELECT COUNT(*) FROM refund_order WHERE order_id = ? AND status = 3", paidOrderId);

        report("实验十五 · 毕业清算（混合订单 + 幂等）",
                "第一轮待清算订单数（期望 2）", first.getTotalOrders(),
                "第一轮：取消待支付 / 退款 / 跳过 / 失败",
                first.getCancelledCount() + " / " + first.getRefundedCount()
                        + " / " + first.getSkippedCount() + " / " + first.getFailedCount(),
                "第一轮退款金额合计（期望 9.00）", first.getTotalRefundAmount(),
                "待支付单状态（期望 5 已退订）", orderStatus(unpaidOrderId),
                "已支付单状态（收敛后，期望 4 已完成）", orderStatus(paidOrderId),
                "已支付单已退款单数（期望 1）", refundRows,
                "第二轮待清算订单数（期望 0 幂等）", second.getTotalOrders());

        assertThat(first.getTotalOrders()).isEqualTo(2);
        assertThat(first.getCancelledCount()).isEqualTo(1);
        assertThat(first.getRefundedCount()).isEqualTo(1);
        assertThat(first.getFailedCount()).isZero();
        assertThat(first.getTotalRefundAmount()).isEqualByComparingTo("9.00");
        assertThat(orderStatus(unpaidOrderId)).isEqualTo(5);
        assertThat(orderStatus(paidOrderId)).isEqualTo(4);
        assertThat(refundRows).isEqualTo(1);
        assertThat(second.getTotalOrders()).isZero();
    }

    @Test
    @DisplayName("申请幂等与拒绝后重新申请：进行中单阻塞新申请，已拒绝(4) 不阻塞")
    void rejectedRefundDoesNotBlockNewApplication() {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(2), 1, 1L);
        Long firstRefundId = refundOrderService.applyRefund(orderId, applyRequest(3, "首次申请"));

        String blockingError = null;
        try {
            refundOrderService.applyRefund(orderId, applyRequest(3, "重复申请"));
        } catch (Exception e) {
            blockingError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        RefundAuditRequest reject = new RefundAuditRequest();
        reject.setApproved(false);
        reject.setRemark("实验：拒绝");
        refundOrderService.audit(firstRefundId, reject);

        Long secondRefundId = refundOrderService.applyRefund(orderId, applyRequest(3, "拒绝后重新申请"));

        report("实验十五 · 申请幂等与重新申请",
                "重复申请被拒原因", blockingError,
                "首单状态（期望 4 已拒绝）", refundOrderService.getById(firstRefundId).getStatus(),
                "重新申请得到新单（期望非空且 ≠ 首单）",
                secondRefundId + "（首单 " + firstRefundId + "）");

        assertThat(blockingError).contains("BusinessException");
        assertThat(refundOrderService.getById(firstRefundId).getStatus()).isEqualTo(REFUND_REJECTED);
        assertThat(secondRefundId).isNotNull().isNotEqualTo(firstRefundId);
    }

    // ==================== 夹具辅助 ====================

    private RefundApplyRequest applyRequest(int boxes, String reason) {
        RefundApplyRequest request = new RefundApplyRequest();
        request.setApplyBoxCount(boxes);
        request.setReason(reason);
        return request;
    }

    /** 申请 + 审核通过，返回退款单 ID（实验主流程都是管理员直接执行，故审核一并走通） */
    private Long applyAndAudit(long orderId, int boxes) {
        Long refundId = refundOrderService.applyRefund(orderId, applyRequest(boxes, "实验申请"));
        RefundAuditRequest approve = new RefundAuditRequest();
        approve.setApproved(true);
        approve.setRemark("实验：审核通过");
        refundOrderService.audit(refundId, approve);
        return refundId;
    }
}
