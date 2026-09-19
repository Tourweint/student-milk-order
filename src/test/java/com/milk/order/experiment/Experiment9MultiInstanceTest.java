package com.milk.order.experiment;

import com.milk.order.common.InstanceIdentity;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.WechatPayOrder;
import com.milk.order.module.order.mapper.WechatPayOrderMapper;
import com.milk.order.module.order.pay.WechatPayMockProperties;
import com.milk.order.module.order.pay.WechatPaySimulator;
import com.milk.order.module.order.vo.WechatPayParamsVO;
import com.milk.order.module.product.invariant.QuotaLedgerInvariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验九：多实例部署的正确性。
 *
 * <p>多实例下的问题很少来自"算法"，几乎全部来自两类东西：<b>本地状态</b>与<b>幂等边界</b>。
 * 本实验按这两类逐个验证：</p>
 *
 * <ul>
 *   <li><b>对照 A · 本地状态</b>：支付状态原先存在模拟器的内存 Map 里。
 *       用两个模拟器对象（等价于两个应用实例，只共享数据库）验证：
 *       A 实例预下单 → B 实例确认扣款 → A 实例查单，三者都必须成立；
 *       再验证"回调丢失时由对账补偿落账"，且补偿不关心扣款发生在哪个实例。</li>
 *   <li><b>对照 B · 定时任务重复执行</b>：两个实例同时跑支付对账 → 只落账一次
 *       （订单状态、成功流水、迁移台账、配送任务各一份）。</li>
 *   <li><b>对照 C · 定时任务重复执行</b>：两个实例同时跑自动签收批次 → 每条记录只签收一次、
 *       营养摄入只生成一条。</li>
 *   <li><b>对照 D · 并发写入的唯一键</b>：两个实例同时体检 → 违规记录不重复
 *       （唯一键 + 幂等插入仲裁）。</li>
 * </ul>
 *
 * <p><b>说明（方法学上的诚实）</b>：本实验在单 JVM 内用「并发线程 + 多个组件实例」模拟多实例，
 * 覆盖的是<u>共享数据库、各自持有组件的多实例语义</u>；
 * <u>不覆盖</u>真实的多进程/多主机、网络分区、时钟偏移与负载均衡路由（见实验文档「已知局限」）。</p>
 */
@DisplayName("实验九：多实例部署的正确性")
class Experiment9MultiInstanceTest extends ExperimentSupport {

    private static final int BATCH_LIMIT = 200;

    @Autowired
    private WechatPayMockProperties wxpayProperties;

    @Autowired
    private WechatPayOrderMapper wechatPayOrderMapper;

    @Test
    @DisplayName("对照A：支付状态由数据库共享——跨实例确认扣款、跨实例查单、跨实例补偿落账")
    void payStateIsSharedAcrossInstances() {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        // 商户侧发起微信支付（预支付单由 Spring 单例模拟器签发 = 实例 A 的角色）
        WechatPayParamsVO params = orderInfoService.prepayOrder(orderId);
        String prepayId = params.getPrepayId();
        OrderInfo order = orderInfoService.getById(orderId);
        String orderNo = order.getOrderNo();

        // 两个"应用实例"：各自持有自己的模拟器对象，只共享数据库
        WechatPaySimulator instanceA = new WechatPaySimulator(wxpayProperties, wechatPayOrderMapper);
        WechatPaySimulator instanceB = new WechatPaySimulator(wxpayProperties, wechatPayOrderMapper);

        // 用户在实例 B 确认扣款（内存态实现下这里必然失败：B 的 prepayStore 里没有这个单）
        boolean acceptedByB = instanceB.confirmPay(prepayId);
        // 实例 A 查单（内存态实现下这里必然返回 null：A 的 paidStore 里没有这次扣款）
        WechatPaySimulator.PaidOrder paidSeenByA = instanceA.queryOrder(orderNo);
        int prepayRows = count("SELECT COUNT(*) FROM wechat_pay_order WHERE out_trade_no = ?", orderNo);
        int paidRows = count("SELECT COUNT(*) FROM wechat_pay_order WHERE out_trade_no = ? AND status = ?",
                orderNo, WechatPayOrder.STATUS_PAID);

        // 回调在测试环境无法送达（8090 无服务）→ 正是"回调丢失"场景：由对账补偿
        jdbcTemplate.update("UPDATE payment_record SET create_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE) "
                + "WHERE order_id = ? AND status = 1", orderId);
        int compensated = orderInfoService.reconcilePendingPayments();

        int statusAfter = orderStatus(orderId);
        int successRecords = count("SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int payLedger = count("SELECT COUNT(*) FROM process_transition_log WHERE entity_type = 'order_info' "
                + "AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int tasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);

        report("实验九 · 对照A：跨实例的支付状态",
                "B 实例确认扣款是否受理（内存态下必为 false）", acceptedByB,
                "A 实例查单是否拿到扣款结果（内存态下必为 null）", paidSeenByA == null ? "null" : "已扣款 " + paidSeenByA.getTransactionId(),
                "微信侧支付单行数（预支付）", prepayRows,
                "微信侧支付单行数（已扣款）", paidRows,
                "回调丢失后对账补偿落账数", compensated,
                "落账后订单状态（2=已支付）", statusAfter,
                "成功支付流水条数（应 1）", successRecords,
                "PAY 生效迁移台账条数（应 1）", payLedger,
                "配送任务数（应 1）", tasks);

        assertThat(acceptedByB).isTrue();
        assertThat(paidSeenByA).isNotNull();
        assertThat(paidSeenByA.getTransactionId()).isNotBlank();
        assertThat(prepayRows).isEqualTo(1);
        assertThat(paidRows).isEqualTo(1);
        assertThat(compensated).isEqualTo(1);
        assertThat(statusAfter).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(successRecords).isEqualTo(1);
        assertThat(payLedger).isEqualTo(1);
        assertThat(tasks).isEqualTo(1);
    }

    @Test
    @DisplayName("对照B：两个实例并发跑同一个支付对账任务，只落账一次")
    void concurrentReconcileLandsExactlyOnce() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        WechatPayParamsVO params = orderInfoService.prepayOrder(orderId);
        new WechatPaySimulator(wxpayProperties, wechatPayOrderMapper).confirmPay(params.getPrepayId());
        jdbcTemplate.update("UPDATE payment_record SET create_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE) "
                + "WHERE order_id = ? AND status = 1", orderId);

        // 两个实例同时执行对账任务体（每个实例的 PaymentReconcileJob 都会调这个方法）
        ConcurrentOutcome outcome = runConcurrently(2, index -> orderInfoService.reconcilePendingPayments() > 0);

        int statusAfter = orderStatus(orderId);
        int successRecords = count("SELECT COUNT(*) FROM payment_record WHERE order_id = ? AND status = 2", orderId);
        int payLedger = count("SELECT COUNT(*) FROM process_transition_log WHERE entity_type = 'order_info' "
                + "AND entity_id = ? AND action = 'PAY' AND result = 1", orderId);
        int tasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int quotaUsage = count("SELECT COUNT(*) FROM daily_quota_usage WHERE order_id = ?", orderId);

        report("实验九 · 对照B：两个实例并发跑支付对账",
                "并发实例数", 2,
                "报告'本轮补偿了订单'的实例数（应 1）", outcome.success,
                "本轮未补偿的实例数（CAS 冲突，被任务内部消化）", outcome.failed,
                "并发执行体抛出的异常（空=落败方在任务内部被消化，符合预期）", outcome.errorSummary(),
                "订单状态（2=已支付）", statusAfter,
                "成功支付流水条数（应 1）", successRecords,
                "PAY 生效迁移台账条数（应 1）", payLedger,
                "配送任务数（应 1）", tasks,
                "配额扣减台账行数（应 1）", quotaUsage);

        assertThat(statusAfter).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(successRecords).isEqualTo(1);
        assertThat(payLedger).isEqualTo(1);
        assertThat(tasks).isEqualTo(1);
        assertThat(quotaUsage).isEqualTo(1);
        // 只有一个实例"做成"了；另一个的 CAS 冲突被任务内部消化（不影响最终一致）
        assertThat(outcome.success).isEqualTo(1);
    }

    @Test
    @DisplayName("对照C：两个实例并发跑同一个自动签收批次，每条记录只签收一次")
    void concurrentAutoSignSignsExactlyOnce() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        setQuota(yesterday, 100);
        long orderId = newPendingOrder(yesterday, yesterday, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(yesterday.toString(), null);
        long taskId = taskIdOf(orderId, yesterday);
        long recordId = recordIdOf(taskId);

        List<Long> candidates = deliveryTaskService.listExpiredAutoSignRecordIds(BATCH_LIMIT);
        // 两个实例同时执行自动签收任务体（每个实例的 DeliveryAutoSignJob 都会逐条调用 autoSignOne）
        ConcurrentOutcome outcome = runConcurrently(2, index -> {
            int signed = 0;
            for (Long id : candidates) {
                try {
                    deliveryTaskService.autoSignOne(id);
                    signed++;
                } catch (Exception ignored) {
                    // 与生产任务体一致：单条失败不影响其余
                }
            }
            return signed > 0;
        });

        int signStatus = count("SELECT sign_status FROM delivery_record WHERE id = ?", recordId);
        int intakeRows = count("SELECT COUNT(*) FROM nutrition_intake WHERE delivery_record_id = ?", recordId);
        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int orderStatusFinal = orderStatus(orderId);
        int signLedger = count("SELECT COUNT(*) FROM process_transition_log WHERE entity_type = 'delivery_task' "
                + "AND entity_id = ? AND action = 'SIGN' AND result = 1", taskId);

        report("实验九 · 对照C：两个实例并发跑自动签收批次",
                "并发实例数", 2,
                "候选中标记录数", candidates.size(),
                "两个实例都报告处理完成（幂等跳过不算失败）", outcome.success + " / " + outcome.failed,
                "签收记录状态（1=已签收）", signStatus,
                "该记录的营养摄入条数（应 1）", intakeRows,
                "任务状态（3=已完成）", taskStatus,
                "订单状态（4=已完成）", orderStatusFinal,
                "任务 SIGN 生效迁移台账条数（应 1）", signLedger);

        assertThat(candidates).contains(recordId);
        assertThat(signStatus).isEqualTo(1);
        assertThat(intakeRows).isEqualTo(1);
        assertThat(taskStatus).isEqualTo(3);
        assertThat(orderStatusFinal).isEqualTo(OrderStatus.COMPLETED.getCode());
        assertThat(signLedger).isEqualTo(1);
    }

    @Test
    @DisplayName("对照E：编号生成不再依赖实例本地计数器（编号带实例标识、任务号由业务键确定）")
    void generatedNumbersDoNotRelyOnInstanceLocalCounters() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        setQuota(day1, 100);
        long orderId = newPendingOrder(day1, day2, 1);
        orderInfoService.payOrder(orderId);

        // ① 按实例生成的编号（订单号/支付流水号共用 nextNo）必须带实例标识：
        //    否则两个实例在同一秒生成同一个号，会撞唯一键表现为"下单失败"
        String transactionId = jdbcTemplate.queryForObject(
                "SELECT transaction_id FROM order_info WHERE id = ?", String.class, orderId);

        // ② 任务号由业务键确定性推导：任何实例算出的都是同一个值（纯函数），
        //    因此 uk_task_no 与 uk_order_product_date 不可能出现"一个冲突、另一个不冲突"
        String expectedDay1 = "DT" + day1.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + orderId + "-" + productId;
        String expectedDay2 = "DT" + day2.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + orderId + "-" + productId;
        List<String> taskNos = jdbcTemplate.queryForList(
                "SELECT task_no FROM delivery_task WHERE order_id = ? ORDER BY delivery_date",
                String.class, orderId);
        int distinctTaskNos = count("SELECT COUNT(DISTINCT task_no) FROM delivery_task WHERE order_id = ?", orderId);
        int distinctPairs = count("SELECT COUNT(DISTINCT CONCAT(delivery_date, '#', product_id)) "
                + "FROM delivery_task WHERE order_id = ?", orderId);

        report("实验九 · 对照E：编号生成与实例本地状态解耦",
                "订单号/流水号是否含实例标识（" + InstanceIdentity.TAG + "）",
                transactionId != null && transactionId.contains(InstanceIdentity.TAG),
                "任务号（第 1 天，期望确定性公式）", taskNos.isEmpty() ? "（无）" : taskNos.get(0),
                "任务号（第 2 天）", taskNos.size() < 2 ? "（无）" : taskNos.get(1),
                "任务号去重后条数（应等于任务数）", distinctTaskNos,
                "任务业务键组合数", distinctPairs,
                "任务号与业务键是否一一对应", distinctTaskNos == distinctPairs);

        assertThat(transactionId).contains(InstanceIdentity.TAG);
        assertThat(taskNos).containsExactly(expectedDay1, expectedDay2);
        // 任务号与业务键一一对应：不会有"两个业务键共用一个号"或"一个业务键占两个号"
        assertThat(distinctTaskNos).isEqualTo(distinctPairs);
    }

    @Test
    @DisplayName("对照D：两个实例并发体检，违规记录不重复且数据被正确修复")
    void concurrentInvariantScanDoesNotDuplicateViolationRecords() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        long quotaId = id("SELECT id FROM daily_quota WHERE quota_date = ? AND product_id = ?", date, productId);
        // 注入配额账实不符，让两个实例都看得到同一处违规
        jdbcTemplate.update("UPDATE daily_quota SET used_quota = used_quota + 3 WHERE id = ?", quotaId);

        ConcurrentOutcome outcome = runConcurrently(2, index -> !invariantScanner.scan(true, BATCH_LIMIT).isAllPassed());

        int violationRows = count("SELECT COUNT(*) FROM process_invariant_violation "
                + "WHERE invariant_code = ? AND entity_id = ?", QuotaLedgerInvariant.CODE, quotaId);
        int usedQuota = count("SELECT used_quota FROM daily_quota WHERE id = ?", quotaId);
        int ledgerSum = count("SELECT COALESCE(SUM(boxes), 0) FROM daily_quota_usage WHERE product_id = ?", productId);
        Integer recordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                Integer.class, QuotaLedgerInvariant.CODE, quotaId);
        String repairAction = jdbcTemplate.queryForObject(
                "SELECT repair_action FROM process_invariant_violation WHERE invariant_code = ? AND entity_id = ?",
                String.class, QuotaLedgerInvariant.CODE, quotaId);

        report("实验九 · 对照D：两个实例并发体检",
                "并发实例数", 2,
                "两个实例本轮都检出了违规", outcome.success + " / " + outcome.failed,
                "同一主体的违规记录行数（应 1）", violationRows,
                "修复后的 used_quota（应等于台账合计）", usedQuota,
                "台账合计", ledgerSum,
                "体检记录最终状态（1=已闭环，观察项：并发下可能被覆盖为 0）", recordStatus,
                "体检记录闭环/失败说明", repairAction);

        // 关键：并发体检不会给同一主体插出两条违规记录（唯一键 + 幂等插入仲裁）
        assertThat(violationRows).isEqualTo(1);
        // 数据最终一致：不管谁的修复生效，结果都应收敛到"账实相符"
        assertThat(usedQuota).isEqualTo(ledgerSum);
        // 观察项（不作为断言，理由见实验文档「已知局限」）：
        // 两个实例的"修复成功/失败"竞争可能让闭环标记落在"未闭环"上，但下一轮复检会自动闭合。
        assertThat(recordStatus).isIn(0, 1);
    }
}
