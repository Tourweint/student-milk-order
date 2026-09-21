package com.milk.order.experiment;

import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.dto.DailyQuotaBatchRequest;
import com.milk.order.module.warehouse.dto.WarehouseAdjustRequest;
import com.milk.order.module.warehouse.dto.WarehouseReceiptRequest;
import com.milk.order.module.warehouse.invariant.WarehouseOutLedgerInvariant;
import com.milk.order.module.warehouse.service.WarehouseService;
import com.milk.order.module.warehouse.vo.WarehouseReceiptVO;
import com.milk.order.process.invariant.InvariantScanReport;
import com.milk.order.process.invariant.InvariantScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验二十：仓库余量台账（供给侧）与配额发行封顶。
 *
 * <p><b>命题</b>：把"仓库余量"引入系统后，履约链起点（到货 → 出库 → 退回）与配额发行之间
 * 必须满足以下性质，且不破坏既有的可靠性约束（幂等 / CAS / 唯一键 / 父子聚合）：</p>
 * <ol>
 *   <li><b>守恒</b>：任意混合负载后 {@code W = ΣIN + ΣIN_BACK + ΣINIT + ΣADJ(带符号) − ΣOUT}，逐品种成立；</li>
 *   <li><b>出库幂等</b>：并发批量送出 → 每个任务恰一条 OUT（CAS 只一方赢 + {@code uk_biz_ref} 兜底）；</li>
 *   <li><b>发行封顶</b>：仓库余量不足时设配额被拒；登记到货补足后放行；</li>
 *   <li><b>短交预警</b>：应到 > 实到时给出预警但**不阻断**登记（分批到货是常态）；</li>
 *   <li><b>退回闭环</b>：拒收 → IN_BACK 回仓 → 次日补送再出库，账实首尾相接（并覆盖<b>单条</b>送出路径的钩子，
 *       它与批量走同一个 {@code casDispatch}，只挂批量会漏账）；</li>
 *   <li><b>多实例</b>：并发登记同一（品种 × 日期 × 凭证号）→ 唯一键仲裁，只落一行；</li>
 *   <li><b>不变量</b>：已送出却缺 OUT 行可自动补写；而"1→4 未送出"的取消任务<b>不得</b>被判成漏记
 *       （判据必须是 {@code dispatch_time} 而不是任务状态——见设计方案 §11 P0-3）。</li>
 * </ol>
 *
 * <p>夹具说明：{@link ExperimentSupport} 会给夹具品种一笔充裕的期初库存（送出会自动记 OUT，
 * 无货会让 W 变负并让健康态基线出现 INV_WAREHOUSE_* 误报）；需要**精确控制余量**的用例
 * （③）自行造品种。</p>
 */
@DisplayName("实验二十：仓库余量台账与配额发行封顶")
class Experiment20WarehouseLedgerTest extends ExperimentSupport {

    private static final int LIMIT = 200;

    @Autowired
    private WarehouseService warehouseService;

    // ==================== ① 守恒 ====================

    @Test
    @DisplayName("① 守恒：混合负载（到货/送出/拒收退回/修正）后恒等式成立")
    void ledgerIdentityHoldsUnderMixedLoad() {
        LocalDate today = LocalDate.now();
        LocalDate next = today.plusDays(1);
        int initW = warehouseService.balanceOf(productId);

        // 到货（人工）：企业多送 4 盒（要 100 到 104）→ 余量自动 +4
        WarehouseReceiptVO receipt = warehouseService.receipt(receiptRequest(today, productId, 104, "MAIN"));
        // 修正（人工）：带符号冲销（+3 补记、−1 破损）
        warehouseService.adjust(adjustRequest(productId, 3, "盘点盈余（实验）"));
        warehouseService.adjust(adjustRequest(productId, -1, "破损冲销（实验）"));

        // 送出（自动 OUT）+ 拒收退回（自动 IN_BACK）
        setQuota(next, 5); // 零散补送要往次日机动池追加配额
        long orderId = newPaidOrder(today, today, 2, null);
        deliveryTaskService.batchStartDelivery(today.toString(), null);
        long taskId = taskIdOf(orderId, today);
        rejectRecord(recordIdOf(taskId), "DAMAGED", "包装破损");

        int serviceW = warehouseService.balanceOf(productId);
        int inSum = ledgerSum("IN", productId);
        int backSum = ledgerSum("IN_BACK", productId);
        int adjSum = ledgerSum("ADJ", productId);
        int outSum = ledgerSum("OUT", productId);
        int identityW = initW + inSum + backSum + adjSum - outSum;

        report("实验二十 · ① 守恒",
                "期初 INIT", initW,
                "到货 IN / 退回 IN_BACK / 修正 ADJ / 送出 OUT",
                inSum + " / " + backSum + " / " + adjSum + " / " + outSum,
                "服务算出的 W", serviceW,
                "恒等式重算的 W（期望一致）", identityW,
                "本次到货响应（应到/实到/缺口）",
                receipt.getExpectedQuantity() + " / " + receipt.getQuantity() + " / " + receipt.getShortfall());

        assertThat(serviceW).isEqualTo(identityW);
        assertThat(inSum).isEqualTo(104);
        assertThat(backSum).isEqualTo(2);
        assertThat(adjSum).isEqualTo(2);   // +3 与 −1：ADJ 带符号，求和为 2
        assertThat(outSum).isEqualTo(2);
        assertThat(serviceW).isGreaterThanOrEqualTo(0);
    }

    // ==================== ② 出库幂等（并发） ====================

    @Test
    @DisplayName("② 出库幂等：16 线程并发批量送出 → 每任务恰一条 OUT")
    void concurrentBatchStartWritesExactlyOneOutPerTask() throws InterruptedException {
        LocalDate today = LocalDate.now();
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            taskIds.add(taskIdOf(newPaidOrder(today, today, 1, null), today));
        }

        AtomicInteger dispatched = new AtomicInteger();
        ConcurrentOutcome outcome = runConcurrently(16, index -> {
            dispatched.addAndGet(deliveryTaskService.batchStartDelivery(today.toString(), null));
            return true;
        });

        int outRows = count("SELECT COUNT(*) FROM warehouse_ledger "
                + "WHERE biz_type = 'OUT' AND product_id = ?", productId);
        int perTaskMax = count("SELECT IFNULL(MAX(c), 0) FROM (SELECT COUNT(*) c FROM warehouse_ledger "
                + "WHERE biz_type = 'OUT' GROUP BY ref_id) x");
        boolean allDispatching = taskIds.stream().allMatch(t -> taskStatusById(t) == 2);

        report("实验二十 · ② 出库幂等",
                "并发线程数", 16,
                "任务数", taskIds.size(),
                "累计 dispatched 返回值（期望 4：每个任务只被送出一次）", dispatched.get(),
                "OUT 台账行数（期望 4）", outRows,
                "单个任务最大 OUT 行数（期望 1）", perTaskMax,
                "全部任务已送出(2)", allDispatching,
                "并发失败摘要", outcome.errorSummary());

        assertThat(dispatched.get()).isEqualTo(4);
        assertThat(outRows).isEqualTo(4);
        assertThat(perTaskMax).isEqualTo(1);
        assertThat(allDispatching).isTrue();
    }

    // ==================== ③ 发行封顶 ====================

    @Test
    @DisplayName("③ 发行封顶：余量 50 设配额 100 被拒；登记到货 60 后放行")
    void quotaIssuanceCappedByWarehouseBalance() {
        LocalDate target = LocalDate.now().plusDays(1);
        long warehouseProductId = productWithStock(50);

        DailyQuotaBatchRequest.Item item = new DailyQuotaBatchRequest.Item();
        item.setProductId(warehouseProductId);
        item.setTotalQuota(100);

        String rejected = null;
        try {
            dailyQuotaService.setQuotaBatch(target, List.of(item), TAG);
        } catch (BusinessException e) {
            rejected = e.getMessage();
        }
        int quotaRowsAfterReject = count("SELECT COUNT(*) FROM daily_quota WHERE quota_date = ? AND product_id = ?",
                target, warehouseProductId);

        // 登记到货 60 → W=110 → 放行
        warehouseService.receipt(receiptRequest(LocalDate.now(), warehouseProductId, 60, "MAIN"));
        String afterStockError = null;
        try {
            dailyQuotaService.setQuotaBatch(target, List.of(item), TAG);
        } catch (Exception e) {
            afterStockError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int totalQuota = count("SELECT IFNULL(SUM(total_quota), 0) FROM daily_quota "
                + "WHERE quota_date = ? AND product_id = ?", target, warehouseProductId);

        report("实验二十 · ③ 发行封顶",
                "W=50 时设配额 100（期望被拒）", rejected,
                "拒绝后配额行数（期望 0，未落库）", quotaRowsAfterReject,
                "登记到货 60 后的 W", warehouseService.balanceOf(warehouseProductId),
                "补货后设配额 100（期望无异常）", afterStockError == null ? "（通过）" : afterStockError,
                "落库配额总额（期望 100）", totalQuota);

        assertThat(rejected).isNotNull();
        assertThat(rejected).contains("仓库余量不足");
        assertThat(quotaRowsAfterReject).isZero();
        assertThat(afterStockError).isNull();
        assertThat(totalQuota).isEqualTo(100);
    }

    // ==================== ④ 短交预警 ====================

    @Test
    @DisplayName("④ 短交预警：应到 100 实到 80 → 预警 + 登记成功 + 余量按实到入账")
    void shortDeliveryWarnsButDoesNotBlock() {
        LocalDate today = LocalDate.now();
        setQuota(today, 100); // 当日计划：机动 100 盒（当天应到）
        int before = warehouseService.balanceOf(productId);

        WarehouseReceiptVO vo = warehouseService.receipt(receiptRequest(today, productId, 80, "MAIN"));

        int inRows = count("SELECT COUNT(*) FROM warehouse_ledger "
                + "WHERE biz_type = 'IN' AND product_id = ? AND biz_date = ? AND receipt_no = 'MAIN'", productId, today);

        report("实验二十 · ④ 短交预警",
                "应到（池剩余 + 待送出任务）", vo.getExpectedQuantity(),
                "实到", vo.getQuantity(),
                "缺口", vo.getShortfall(),
                "预警消息", vo.getWarning(),
                "余量增量（期望 = 实到 80）", warehouseService.balanceOf(productId) - before,
                "IN 台账行数（期望 1：预警不阻断登记）", inRows);

        assertThat(vo.getExpectedQuantity()).isEqualTo(100);
        assertThat(vo.getShortfall()).isEqualTo(20);
        assertThat(vo.getWarning()).contains("缺口 20 盒");
        assertThat(vo.getBalance()).isEqualTo(warehouseService.balanceOf(productId));
        assertThat(warehouseService.balanceOf(productId) - before).isEqualTo(80);
        assertThat(inRows).isEqualTo(1);
    }

    // ==================== ⑤ 退回闭环（含单条送出路径的钩子） ====================

    @Test
    @DisplayName("⑤ 退回闭环：送出出库 → 拒收回仓 → 次日补送再出库（单条路径同样记 OUT）")
    void rejectThenCompensationClosesTheLoop() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);
        long orderId = newPaidOrder(day1, day1, 1, null);
        setQuota(day2, 5); // 零散补送：往次日池追加配额（否则补送落库会因池不存在而失败）
        int before = warehouseService.balanceOf(productId);

        deliveryTaskService.batchStartDelivery(day1.toString(), null);
        long taskId = taskIdOf(orderId, day1);
        long recordId = recordIdOf(taskId);
        int afterOut = warehouseService.balanceOf(productId);

        rejectRecord(recordId, "SOUR", "变质异味");
        int afterBack = warehouseService.balanceOf(productId);

        long compensationTaskId = taskIdOf(orderId, day2);
        deliveryTaskService.startDelivery(compensationTaskId); // 单条路径
        int afterCompensationOut = warehouseService.balanceOf(productId);

        report("实验二十 · ⑤ 退回闭环",
                "期初 W（相对值 0）", 0,
                "送出后 W（期望 −1）", afterOut - before,
                "拒收回仓后 W（期望 0）", afterBack - before,
                "次日补送出库后 W（期望 −1）", afterCompensationOut - before,
                "原任务 OUT 行数（期望 1）", ledgerRows("OUT", taskId),
                "原记录 IN_BACK 行数（期望 1）", ledgerRows("IN_BACK", recordId),
                "补送任务 OUT 行数（期望 1：单条路径钩子）", ledgerRows("OUT", compensationTaskId),
                "补送任务状态（期望 2 配送中）", taskStatusById(compensationTaskId));

        assertThat(afterOut - before).isEqualTo(-1);
        assertThat(afterBack - before).isZero();
        assertThat(afterCompensationOut - before).isEqualTo(-1);
        assertThat(ledgerRows("OUT", taskId)).isEqualTo(1);
        assertThat(ledgerRows("IN_BACK", recordId)).isEqualTo(1);
        assertThat(ledgerRows("OUT", compensationTaskId)).isEqualTo(1);
        assertThat(taskStatusById(compensationTaskId)).isEqualTo(2);
    }

    // ==================== ⑥ 多实例并发登记 ====================

    @Test
    @DisplayName("⑥ 多实例：并发登记同一（品种 × 日期 × 凭证号）→ 唯一键仲裁只落一行")
    void concurrentReceiptIsArbitratedByUniqueKey() throws InterruptedException {
        LocalDate today = LocalDate.now();
        int before = warehouseService.balanceOf(productId);

        ConcurrentOutcome outcome = runConcurrently(8, index -> {
            try {
                warehouseService.receipt(receiptRequest(today, productId, 10, "T1"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        int rows = count("SELECT COUNT(*) FROM warehouse_ledger "
                + "WHERE biz_type = 'IN' AND product_id = ? AND biz_date = ? AND receipt_no = 'T1'", productId, today);

        report("实验二十 · ⑥ 多实例并发登记",
                "并发线程数", 8,
                "成功提交数（期望 1）", outcome.success,
                "被唯一键挡回数（期望 7）", outcome.failed,
                "IN 台账行数（期望 1）", rows,
                "余量增量（期望 10，只落账一次）", warehouseService.balanceOf(productId) - before);

        assertThat(outcome.success).isEqualTo(1);
        assertThat(rows).isEqualTo(1);
        assertThat(warehouseService.balanceOf(productId) - before).isEqualTo(10);
    }

    // ==================== ⑦ 不变量（判据 + 自动补写 + 负向对照） ====================

    @Test
    @DisplayName("⑦ 不变量：已送出缺 OUT 行自动补写；1→4 未送出任务不得被判成漏记")
    void outLedgerInvariantRepairsMissingRowButIgnoresNeverDispatchedTask() {
        LocalDate today = LocalDate.now();
        // 任务 A：正常送出（有 dispatch_time，有 OUT 行）
        long dispatchedOrder = newPaidOrder(today, today, 1, null);
        deliveryTaskService.batchStartDelivery(today.toString(), null);
        long dispatchedTaskId = taskIdOf(dispatchedOrder, today);
        String dispatchDate = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(dispatch_time, '%Y-%m-%d') FROM delivery_task WHERE id = ?",
                String.class, dispatchedTaskId);

        // 任务 B：模拟"缺货取消 / 家长豁免"那一类 1→4 —— 从未送出（dispatch_time 为空）
        long cancelledOrder = newPaidOrder(today, today, 1, null);
        long neverDispatchedTaskId = taskIdOf(cancelledOrder, today);
        jdbcTemplate.update("UPDATE delivery_task SET status = 4 WHERE id = ?", neverDispatchedTaskId);

        // 注入"漏记出库"：删掉 A 的 OUT 行
        jdbcTemplate.update("DELETE FROM warehouse_ledger WHERE biz_type = 'OUT' AND ref_id = ?", dispatchedTaskId);

        InvariantScanReport scan = invariantScanner.scan(true, LIMIT);
        InvariantScanResult result = scan.resultOf(WarehouseOutLedgerInvariant.CODE);
        int restoredRows = count("SELECT COUNT(*) FROM warehouse_ledger WHERE biz_type = 'OUT' AND ref_id = ?",
                dispatchedTaskId);
        String restoredDate = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(biz_date, '%Y-%m-%d') FROM warehouse_ledger WHERE biz_type = 'OUT' AND ref_id = ?",
                String.class, dispatchedTaskId);

        report("实验二十 · ⑦ 不变量 INV_LEDGER_OUT_TASK",
                "注入：删除已送出任务的 OUT 行", 1,
                "负向对照：1→4 未送出任务（不得计入）", 1,
                "检出数（期望 1：只认已送出的那条）", result == null ? -1 : result.getDetected(),
                "自动修复数（期望 1）", result == null ? -1 : result.getRepaired(),
                "补写后 OUT 行数（期望 1）", restoredRows,
                "补写的 biz_date（= 送出日）", restoredDate);

        assertThat(result).isNotNull();
        assertThat(result.getDetected()).isEqualTo(1);
        assertThat(result.getRepaired()).isEqualTo(1);
        assertThat(restoredRows).isEqualTo(1);
        assertThat(restoredDate).isEqualTo(dispatchDate);
        // 负向对照：1→4 任务始终没有 OUT 行，但它本就不该有
        assertThat(ledgerRows("OUT", neverDispatchedTaskId)).isZero();
    }

    // ==================== 工具 ====================

    private WarehouseReceiptRequest receiptRequest(LocalDate bizDate, long targetProductId, int quantity,
                                                   String receiptNo) {
        WarehouseReceiptRequest request = new WarehouseReceiptRequest();
        request.setBizDate(bizDate);
        request.setProductId(targetProductId);
        request.setQuantity(quantity);
        request.setReceiptNo(receiptNo);
        request.setNote(TAG);
        return request;
    }

    private WarehouseAdjustRequest adjustRequest(long targetProductId, int quantity, String reason) {
        WarehouseAdjustRequest request = new WarehouseAdjustRequest();
        request.setProductId(targetProductId);
        request.setQuantity(quantity);
        request.setReason(reason);
        return request;
    }

    /** 造一个独立品种并给定精确的期初库存（夹具品种的期初是为"健康态零检出"预留的巨额，无法用于封顶用例） */
    private long productWithStock(int boxes) {
        String name = TAG + "-W" + boxes;
        jdbcTemplate.update("INSERT INTO product (product_name, category_id, spec, price, status, sort, deleted) "
                + "VALUES (?, ?, '250ml', ?, 1, 0, 0)", name, categoryId, UNIT_PRICE);
        long warehouseProductId = id("SELECT id FROM product WHERE product_name = ?", name);
        jdbcTemplate.update("INSERT INTO warehouse_ledger "
                + "(biz_type, biz_date, product_id, quantity, receipt_no, reason, operator, deleted) "
                + "VALUES ('INIT', CURDATE(), ?, ?, 'INIT', ?, 'system', 0)", warehouseProductId, boxes, TAG);
        return warehouseProductId;
    }

    /** 台账中某类型的盒数合计（用于独立重算恒等式） */
    private int ledgerSum(String bizType, long targetProductId) {
        return count("SELECT IFNULL(SUM(quantity), 0) FROM warehouse_ledger "
                + "WHERE biz_type = ? AND product_id = ? AND deleted = 0", bizType, targetProductId);
    }

    /** 台账中某关联对象的行数（OUT 挂任务、IN_BACK 挂签收记录） */
    private int ledgerRows(String bizType, long refId) {
        return count("SELECT COUNT(*) FROM warehouse_ledger WHERE biz_type = ? AND ref_id = ?", bizType, refId);
    }

    private int taskStatusById(long taskId) {
        Integer status = jdbcTemplate.queryForObject("SELECT status FROM delivery_task WHERE id = ?",
                Integer.class, taskId);
        return status == null ? -1 : status;
    }
}
