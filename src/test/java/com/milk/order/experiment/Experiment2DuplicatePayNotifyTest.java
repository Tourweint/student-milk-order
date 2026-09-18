package com.milk.order.experiment;

import com.milk.order.common.enums.OrderStatus;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验二：同一支付回调并发重复到达的幂等落账。
 *
 * <p>场景：模拟微信在回调超时/网络抖动下把同一笔支付成功通知重复投递，
 * 本实验一次性并发投递 N 次同一回调报文。</p>
 *
 * <p>期望的不变量（重复回调不得产生重复副作用）：</p>
 * <ol>
 *   <li>订单只发生一次 待支付→已支付 迁移；</li>
 *   <li>支付成功流水只有一条；</li>
 *   <li>迁移台账中该订单 PAY 的“已生效”记录只有一条，其余为 CAS 冲突记录；</li>
 *   <li>配送任务数量 = 理论数量（不重复展开）；</li>
 *   <li>配额只被扣减一次。</li>
 * </ol>
 */
@DisplayName("实验二：重复支付回调的幂等落账")
class Experiment2DuplicatePayNotifyTest extends ExperimentSupport {

    private static final int NOTIFY_TIMES = 20;
    private static final int ORDER_BOXES = 2;
    private static final DateTimeFormatter PAY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    @DisplayName("同一回调并发投递 20 次，只落账一次")
    void duplicateNotifySettlesExactlyOnce() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 1000);
        long orderId = newPendingOrder(date, date, ORDER_BOXES);

        String orderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM order_info WHERE id = ?", String.class, orderId);
        java.math.BigDecimal payAmount = jdbcTemplate.queryForObject(
                "SELECT pay_amount FROM order_info WHERE id = ?", java.math.BigDecimal.class, orderId);

        WechatPayNotifyRequest notify = new WechatPayNotifyRequest();
        notify.setOutTradeNo(orderNo);
        notify.setTransactionId("MOCK-TX-" + TAG);
        notify.setAmount(payAmount);
        notify.setPayTime(LocalDateTime.now().format(PAY_TIME_FMT));
        notify.setResultCode("SUCCESS");

        ConcurrentOutcome outcome = runConcurrently(NOTIFY_TIMES, i ->
                orderInfoService.handleWechatPayNotify(notify));

        int paidRecords = count("SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int appliedTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int conflictedTransitions = count("SELECT COUNT(*) FROM process_transition_log "
                + "WHERE entity_type = 'order_info' AND entity_id = ? AND action = 'PAY' AND result = 0", orderId);
        int tasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int usedBoxes = count("SELECT COALESCE(SUM(boxes), 0) FROM daily_quota_usage WHERE order_id = ?", orderId);

        report("实验二：重复支付回调（" + NOTIFY_TIMES + " 次并发）",
                "回调投递次数", NOTIFY_TIMES,
                "回调处理成功（含幂等快速路径）", outcome.success,
                "回调处理失败（CAS 冲突/异常）", outcome.failed,
                "订单最终状态", orderStatus(orderId) + "（2=已支付）",
                "支付成功流水条数", paidRecords,
                "PAY 迁移生效条数", appliedTransitions,
                "PAY 迁移冲突条数", conflictedTransitions,
                "配送任务条数（理论 1）", tasks,
                "配额扣减盒数（理论 " + ORDER_BOXES + "）", usedBoxes,
                "失败原因分布", outcome.errorSummary());

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(paidRecords).isEqualTo(1);
        assertThat(appliedTransitions).isEqualTo(1);
        assertThat(tasks).isEqualTo(1);
        assertThat(usedBoxes).isEqualTo(ORDER_BOXES);

        // 回归断言：重复回调的落败方必须是“CAS 冲突抛异常”，而不是被状态机规则表静默拒绝。
        // 修复前 StateMachineServiceImpl 用 clear()+putAll() 刷新规则缓存，并发请求会在清空窗口内
        // 读到空规则表，被白名单语义判为禁止，于是以“规则禁止”静默返回 false（errors 为空）——
        // 这条断言正是针对该缺陷留下的守卫。
        if (outcome.failed > 0) {
            assertThat(outcome.errors).isNotEmpty();
            assertThat(outcome.errors).allMatch(e -> e.contains("回调处理冲突"));
        }
    }
}
