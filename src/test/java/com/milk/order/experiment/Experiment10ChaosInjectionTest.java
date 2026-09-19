package com.milk.order.experiment;

import com.milk.order.chaos.ChaosInjector;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.pay.WechatPaySimulator;
import com.milk.order.module.order.vo.WechatPayParamsVO;
import com.milk.order.process.invariant.InvariantScanReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十：混沌注入——「事务做到一半失败」下的原子性与最终收敛。
 *
 * <p>实验一~九验证的是正确性与恢复能力，本实验验证的是**中间失败**：
 * 不变量与幂等机制面对的不是"完整的坏数据"，而是"事务执行到一半被打断"。</p>
 *
 * <p>注入方式：测试域的 AOP（{@link ChaosInjector}）在真实事务的中间步骤
 * （扣配额 / 展开任务 / 签收）**执行完之后**抛异常——这精确对应
 * "该步骤的写入已发生、整个事务尚未提交"的语义，因此可以验证：
 * ① 事务回滚不留下半截数据；② 被打断的业务由通道收敛到合法状态。</p>
 *
 * <p>注入是**可复现的**：固定种子的随机数 + 限次注入，重复执行得到同一注入序列。</p>
 */
@DisplayName("实验十：混沌注入——事务中间故障的原子性与最终收敛")
class Experiment10ChaosInjectionTest extends ExperimentSupport {

    private static final int LIMIT = 500;
    private static final DateTimeFormatter PAY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private WechatPaySimulator wechatPaySimulator;

    @AfterEach
    void disarmChaos() {
        // 无论用例成功与否都撤防，避免影响其它用例
        ChaosInjector.disarm();
    }

    @Test
    @DisplayName("对照A：支付落账中途失败——事务整体回滚，由对账通道收敛")
    void chaosDuringPaymentLandingLeavesNoPartialData() {
        ChaosInjector.reset();
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        WechatPayParamsVO params = orderInfoService.prepayOrder(orderId);
        // 微信侧已扣款（回调故意不送达），因此对账查单时能查到
        wechatPaySimulator.confirmPay(params.getPrepayId());
        OrderInfo order = orderInfoService.getById(orderId);
        WechatPaySimulator.PaidOrder paid = wechatPaySimulator.queryOrder(order.getOrderNo());

        // 在「扣配额」这一步执行完之后注入失败（此时订单状态抢占与迁移台账已写入同一事务）
        ChaosInjector.arm(20260919L, 1.0, 1, "DailyQuotaService.deduct");
        String failure = null;
        try {
            orderInfoService.handleWechatPayNotify(notifyOf(paid));
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        ChaosInjector.disarm();

        // 失败后：整个支付事务回滚，不留半截数据
        int statusAfterChaos = orderStatus(orderId);
        int successRecordsAfterChaos = count(
                "SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int tasksAfterChaos = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int usedAfterChaos = usedQuota(date);
        int quotaLedgerAfterChaos = count("SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ?", orderId);
        int payLedgerAfterChaos = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);

        // 对账通道收敛：查单发现已扣款 → 补偿落账
        jdbcTemplate.update("UPDATE payment_record SET create_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE) "
                + "WHERE order_id = ? AND status = 1", orderId);
        int compensated = orderInfoService.reconcilePendingPayments();

        int statusFinal = orderStatus(orderId);
        int successRecordsFinal = count(
                "SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int tasksFinal = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int usedFinal = usedQuota(date);
        int quotaLedgerFinal = count("SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ?", orderId);
        int payLedgerFinal = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int violations = invariantScanner.scan(true, LIMIT).getTotalDetected();

        report("实验十 · 对照A：支付落账事务中途注入失败",
                "注入点", "DailyQuotaService.deduct（扣配额）",
                "捕获到的异常", failure,
                "失败后 · 订单状态（1=待支付）", statusAfterChaos,
                "失败后 · 成功支付流水条数（应 0）", successRecordsAfterChaos,
                "失败后 · 配送任务条数（应 0）", tasksAfterChaos,
                "失败后 · 配额已售数（应 0，扣减随事务回滚）", usedAfterChaos,
                "失败后 · 配额台账行数（应 0）", quotaLedgerAfterChaos,
                "失败后 · PAY 生效迁移台账条数（应 0）", payLedgerAfterChaos,
                "对账通道补偿落账数（应 1）", compensated,
                "收敛后 · 订单状态（2=已支付）", statusFinal,
                "收敛后 · 成功流水/任务/配额/台账/PAY 台账",
                successRecordsFinal + " / " + tasksFinal + " / " + usedFinal + " / "
                        + quotaLedgerFinal + " / " + payLedgerFinal,
                "收敛后 · 全库不变量检出数（应 0）", violations);

        assertThat(failure).contains("ChaosInjectedException");
        // ① 原子性：中间失败不留下任何半截数据（状态、流水、任务、配额、台账全部回到原点）
        assertThat(statusAfterChaos).isEqualTo(OrderStatus.PENDING_PAYMENT.getCode());
        assertThat(successRecordsAfterChaos).isZero();
        assertThat(tasksAfterChaos).isZero();
        assertThat(usedAfterChaos).isZero();
        assertThat(quotaLedgerAfterChaos).isZero();
        assertThat(payLedgerAfterChaos).isZero();
        // ② 最终收敛：对账通道把中断的支付补上，且各写一份
        assertThat(compensated).isEqualTo(1);
        assertThat(statusFinal).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(successRecordsFinal).isEqualTo(1);
        assertThat(tasksFinal).isEqualTo(1);
        assertThat(usedFinal).isEqualTo(1);
        assertThat(quotaLedgerFinal).isEqualTo(1);
        assertThat(payLedgerFinal).isEqualTo(1);
        // ③ 全库不变量成立
        assertThat(violations).isZero();
    }

    @Test
    @DisplayName("对照B：任务展开中途失败——已扣配额与已写流水一并回滚")
    void chaosDuringTaskGenerationRollsBackEverything() {
        ChaosInjector.reset();
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);

        // 支付事务顺序：抢占状态 → 扣配额 → 写流水 → 展开任务；在最后一步注入失败
        ChaosInjector.arm(20260919L, 1.0, 1, "DeliveryTaskService.generateTasksForOrder");
        String failure = null;
        try {
            orderInfoService.payOrder(orderId);
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        ChaosInjector.disarm();

        int statusAfterChaos = orderStatus(orderId);
        int usedAfterChaos = usedQuota(date);
        int quotaLedgerAfterChaos = count("SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ?", orderId);
        int successRecordsAfterChaos = count(
                "SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int tasksAfterChaos = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int recordsAfterChaos = count("SELECT COUNT(*) FROM delivery_record r "
                + "JOIN delivery_task t ON r.task_id = t.id WHERE t.order_id = ?", orderId);

        // 撤防后重试同一动作（用户重新支付），应当完整成功
        orderInfoService.payOrder(orderId);

        int statusFinal = orderStatus(orderId);
        int usedFinal = usedQuota(date);
        int tasksFinal = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int recordsFinal = count("SELECT COUNT(*) FROM delivery_record r "
                + "JOIN delivery_task t ON r.task_id = t.id WHERE t.order_id = ?", orderId);
        int payLedgerFinal = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int violations = invariantScanner.scan(true, LIMIT).getTotalDetected();

        report("实验十 · 对照B：任务展开中途注入失败（支付事务的最后一步）",
                "注入点", "DeliveryTaskService.generateTasksForOrder（展开任务网格）",
                "捕获到的异常", failure,
                "失败后 · 订单状态（1=待支付）", statusAfterChaos,
                "失败后 · 配额已售数（应 0）", usedAfterChaos,
                "失败后 · 配额台账行数（应 0）", quotaLedgerAfterChaos,
                "失败后 · 成功支付流水条数（应 0）", successRecordsAfterChaos,
                "失败后 · 任务/签收记录条数（应 0 / 0）", tasksAfterChaos + " / " + recordsAfterChaos,
                "重试后 · 订单状态（2=已支付）", statusFinal,
                "重试后 · 配额已售数（应 1）", usedFinal,
                "重试后 · 任务/签收记录条数（应 1 / 1）", tasksFinal + " / " + recordsFinal,
                "重试后 · PAY 生效迁移台账条数（应 1）", payLedgerFinal,
                "重试后 · 全库不变量检出数（应 0）", violations);

        assertThat(failure).contains("ChaosInjectedException");
        // 在事务的最后一步失败，此前所有写入（状态、配额、流水）都必须回滚
        assertThat(statusAfterChaos).isEqualTo(OrderStatus.PENDING_PAYMENT.getCode());
        assertThat(usedAfterChaos).isZero();
        assertThat(quotaLedgerAfterChaos).isZero();
        assertThat(successRecordsAfterChaos).isZero();
        assertThat(tasksAfterChaos).isZero();
        assertThat(recordsAfterChaos).isZero();
        // 重试路径完整成功，且不产生重复
        assertThat(statusFinal).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(usedFinal).isEqualTo(1);
        assertThat(tasksFinal).isEqualTo(1);
        assertThat(recordsFinal).isEqualTo(1);
        assertThat(payLedgerFinal).isEqualTo(1);
        assertThat(violations).isZero();
    }

    @Test
    @DisplayName("对照C：签收中途失败——记录/任务/营养/聚合一并回滚")
    void chaosDuringSignRollsBackWholeSignFlow() {
        ChaosInjector.reset();
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        long taskId = taskIdOf(orderId, date);
        long recordId = recordIdOf(taskId);

        // 注入点在签收事务**内部**的最后一步（父状态聚合）：此时记录/任务/营养写入都已完成、
        // 事务尚未提交，因此回滚必须把整条链路一并撤销
        ChaosInjector.arm(20260919L, 1.0, 1, "OrderInfoService.completeOrderIfAllTasksDone");
        String failure = null;
        try {
            signRecord(recordId);
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        ChaosInjector.disarm();

        int recordAfterChaos = count("SELECT sign_status FROM delivery_record WHERE id = ?", recordId);
        int taskAfterChaos = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int intakeAfterChaos = count("SELECT COUNT(*) FROM nutrition_intake WHERE delivery_record_id = ?", recordId);
        int orderAfterChaos = orderStatus(orderId);

        // 撤防后重新签收
        signRecord(recordId);

        int recordFinal = count("SELECT sign_status FROM delivery_record WHERE id = ?", recordId);
        int taskFinal = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int intakeFinal = count("SELECT COUNT(*) FROM nutrition_intake WHERE delivery_record_id = ?", recordId);
        int orderFinal = orderStatus(orderId);
        int signLedger = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'delivery_task' AND entity_id = ? AND action = 'SIGN' AND result = 1", taskId);
        int violations = invariantScanner.scan(true, LIMIT).getTotalDetected();

        report("实验十 · 对照C：签收中途注入失败",
                "注入点", "OrderInfoService.completeOrderIfAllTasksDone（签收事务内的父状态聚合）",
                "捕获到的异常", failure,
                "失败后 · 签收记录状态（应 2=未签收）", recordAfterChaos,
                "失败后 · 任务状态（应 2=配送中）", taskAfterChaos,
                "失败后 · 营养摄入条数（应 0）", intakeAfterChaos,
                "失败后 · 订单状态（应 3=配送中）", orderAfterChaos,
                "重试后 · 签收记录状态（1=已签收）", recordFinal,
                "重试后 · 任务状态（3=已完成）", taskFinal,
                "重试后 · 营养摄入条数（应 1）", intakeFinal,
                "重试后 · 订单状态（4=已完成）", orderFinal,
                "重试后 · SIGN 生效迁移台账条数（应 1）", signLedger,
                "重试后 · 全库不变量检出数（应 0）", violations);

        assertThat(failure).contains("ChaosInjectedException");
        assertThat(recordAfterChaos).isEqualTo(2);
        assertThat(taskAfterChaos).isEqualTo(2);
        assertThat(intakeAfterChaos).isZero();
        assertThat(orderAfterChaos).isEqualTo(OrderStatus.DELIVERING.getCode());
        // 重试后整条链路一次性完成，没有重复副作用
        assertThat(recordFinal).isEqualTo(1);
        assertThat(taskFinal).isEqualTo(3);
        assertThat(intakeFinal).isEqualTo(1);
        assertThat(orderFinal).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(signLedger).isEqualTo(1);
        assertThat(violations).isZero();
    }

    @Test
    @DisplayName("对照D：混沌风暴下的最终收敛——所有订单合法且全库不变量成立")
    void stormOfInjectionsStillConverges() throws Exception {
        ChaosInjector.reset();
        LocalDate date = LocalDate.now().plusDays(1);
        int orderCount = 6;
        setQuota(date, 1000);

        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            long orderId = newPendingOrder(date, date, 1);
            orderIds.add(orderId);
            WechatPayParamsVO params = orderInfoService.prepayOrder(orderId);
            wechatPaySimulator.confirmPay(params.getPrepayId());
        }

        // 阶段一：带混沌地把 6 笔支付落账推进一遍（按 60% 概率注入、最多注入 4 次）
        ChaosInjector.arm(20260919L, 0.6, 4);
        int failures = 0;
        List<Long> unpaid = new ArrayList<>();
        for (Long orderId : orderIds) {
            OrderInfo order = orderInfoService.getById(orderId);
            WechatPaySimulator.PaidOrder paid = wechatPaySimulator.queryOrder(order.getOrderNo());
            try {
                orderInfoService.handleWechatPayNotify(notifyOf(paid));
            } catch (Exception e) {
                failures++;
                unpaid.add(orderId);
            }
        }
        ChaosInjector.disarm();
        int injected = ChaosInjector.injected();

        // 阶段二：被打断的支付交给对账通道（回调丢失场景）
        jdbcTemplate.update("UPDATE payment_record SET create_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE) "
                + "WHERE status = 1");
        int compensated = orderInfoService.reconcilePendingPayments();

        // 阶段三：人为制造"父状态漂移"（直接改库模拟写入丢失），交给双通道收敛
        //   只注入**规则表覆盖得到**的漂移：已支付 + 有配送中任务 → 命中「联动丢失补偿」
        for (Long orderId : orderIds) {
            if (orderStatus(orderId) == OrderStatus.PAID.getCode()) {
                deliveryTaskService.batchStartDelivery(date.toString(), null);
                break; // 批量送出一次即覆盖当日全部订单
            }
        }
        int drifts = 0;
        for (Long orderId : orderIds) {
            if (orderStatus(orderId) == OrderStatus.DELIVERING.getCode()) {
                // 把父状态回退为已支付：子任务仍为配送中 → 命中「联动丢失补偿」
                jdbcTemplate.update("UPDATE order_info SET status = ? WHERE id = ?",
                        OrderStatus.PAID.getCode(), orderId);
                drifts++;
            }
        }
        int[] rounds = new int[1];
        int channelRepaired = convergeBothChannels(LIMIT, 5, rounds);

        // 断言：所有订单已收敛到合法状态，且各只有一份副作用
        int paidCount = count("SELECT COUNT(*) FROM order_info WHERE status = ?", OrderStatus.PAID.getCode());
        int deliveringCount = count("SELECT COUNT(*) FROM order_info WHERE status = ?",
                OrderStatus.DELIVERING.getCode());
        int completedCount = count("SELECT COUNT(*) FROM order_info WHERE status = ?",
                OrderStatus.COMPLETED.getCode());
        int illegalCount = count("SELECT COUNT(*) FROM order_info WHERE status NOT IN (2, 3, 4)");
        int duplicateRecords = count("SELECT COUNT(*) FROM (SELECT order_id FROM payment_record "
                + "WHERE status = 2 GROUP BY order_id HAVING COUNT(*) > 1) t");
        int duplicateLedger = count("SELECT COUNT(*) FROM (SELECT entity_id FROM process_transition_log "
                + "WHERE action = 'PAY' AND result = 1 GROUP BY entity_id HAVING COUNT(*) > 1) t");
        InvariantScanReport finalScan = invariantScanner.scan(true, LIMIT);

        report("实验十 · 对照D：混沌风暴下的最终收敛",
                "订单数", orderCount,
                "阶段一 · 注入次数 / 实际命中调用数", injected + " / " + ChaosInjector.matched(),
                "阶段一 · 因注入而失败的回调数", failures,
                "阶段二 · 对账通道补偿落账数", compensated,
                "阶段三 · 人为制造的漂移数", drifts,
                "阶段三 · 双通道补偿生效数 / 收敛轮次", channelRepaired + " / " + rounds[0],
                "最终 · 订单状态分布（已支付/配送中/已完成）",
                paidCount + " / " + deliveringCount + " / " + completedCount,
                "最终 · 非合法状态订单数（应 0）", illegalCount,
                "最终 · 重复成功流水的订单数（应 0）", duplicateRecords,
                "最终 · 重复 PAY 生效台账的订单数（应 0）", duplicateLedger,
                "最终 · 全库不变量检出数（应 0）", finalScan.getTotalDetected(),
                "最终 · 未闭环数（应 0）", finalScan.getTotalOpen());

        assertThat(injected).isGreaterThan(0);          // 确实注入了（否则实验无效）
        assertThat(failures).isGreaterThan(0);
        assertThat(compensated).isEqualTo(failures);    // 每一笔被打断的支付都被对账补上
        assertThat(drifts).isGreaterThan(0);
        assertThat(channelRepaired).isEqualTo(drifts);  // 每一处漂移都被通道收掉
        assertThat(illegalCount).isZero();
        assertThat(duplicateRecords).isZero();
        assertThat(duplicateLedger).isZero();
        assertThat(finalScan.getTotalDetected()).isZero();
        assertThat(finalScan.getTotalOpen()).isZero();
    }

    private WechatPayNotifyRequest notifyOf(WechatPaySimulator.PaidOrder paid) {
        WechatPayNotifyRequest notify = new WechatPayNotifyRequest();
        notify.setOutTradeNo(paid.getOutTradeNo());
        notify.setTransactionId(paid.getTransactionId());
        notify.setAmount(paid.getAmount());
        notify.setPayTime(paid.getPayTime().format(PAY_TIME_FMT));
        notify.setResultCode("SUCCESS");
        return notify;
    }
}
