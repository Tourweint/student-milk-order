package com.milk.order.contract;

import com.milk.order.experiment.ExperimentSupport;
import com.milk.order.module.delivery.dto.StockoutCancelRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：不变量 `INV_TASK_COMPENSATION`。
 *
 * <p>它守的是"真拒收必须有补偿记录"，判据是 `sign_status=3 AND reject_reason_code IS NOT NULL`——
 * 退订联动与缺货取消经 {@code markRecordRejected} 也会把记录置为 {@code sign_status=3}，
 * 但它们不写 `reject_reason_code`，因此**不能**被当成"拒收未补送"。
 * 处置等级为 `ALERT_ONLY`：补送缺失涉及配额与业务判断，不得自动修复。</p>
 */
@DisplayName("契约测试：INV_TASK_COMPENSATION")
class TaskCompensationInvariantContractTest extends ExperimentSupport {

    private static final String CODE = "INV_TASK_COMPENSATION";

    @Test
    @DisplayName("真拒收缺补偿被检出（ALERT_ONLY 不自动修）；退订/缺货取消不误报")
    void detectsMissingCompensationOnlyForRealReject() {
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = day1.plusDays(1);

        // ① 真拒收（写了原因分类）→ 已产生补偿 → 体检不应检出
        long orderId = newPaidOrder(day1, day2, 1, 1L);
        deliveryTaskService.batchStartDelivery(day1.toString(), null);
        rejectRecord(recordIdOf(taskIdOf(orderId, day1)), "SOUR", "变质异味");
        invariantScanner.scan(true, 20);
        int healthy = count("SELECT COUNT(*) FROM process_invariant_violation WHERE invariant_code = ?", CODE);

        // ② 抹掉补偿记录 → 应检出 1 条未闭环；ALERT_ONLY 不做自动修复
        jdbcTemplate.update("DELETE FROM delivery_compensation");
        invariantScanner.scan(true, 20);
        int openAfterTamper = count(
                "SELECT COUNT(*) FROM process_invariant_violation WHERE invariant_code = ? AND status = 0", CODE);
        String severity = jdbcTemplate.queryForObject(
                "SELECT severity FROM process_invariant_violation WHERE invariant_code = ? LIMIT 1", String.class, CODE);
        int compensationAfterScan = compensationCount(null);

        // ③ 缺货取消（记录被置 3 但没写原因分类）不应被计入
        long otherOrder = newPaidOrder(day1, day2, 1, 1L);
        StockoutCancelRequest request = new StockoutCancelRequest();
        request.setDeliveryDate(day1.toString());
        request.setProductId(productId);
        deliveryTaskService.stockoutCancel(request);
        invariantScanner.scan(true, 20);
        int afterStockoutCancel = count("SELECT COUNT(*) FROM process_invariant_violation WHERE invariant_code = ?", CODE);

        report("契约测试 · INV_TASK_COMPENSATION",
                "① 健康（有补偿）检出（期望 0）", healthy,
                "② 抹掉补偿后未闭环检出（期望 1）", openAfterTamper,
                "② 处置等级（期望 ALERT_ONLY）", severity,
                "② 自动修复后补偿记录数（期望 0，不自动修）", compensationAfterScan,
                "③ 缺货取消后总检出（期望仍 1，不误报）", afterStockoutCancel);

        assertThat(healthy).isZero();
        assertThat(openAfterTamper).isEqualTo(1);
        assertThat(severity).isEqualTo("ALERT_ONLY");
        assertThat(compensationAfterScan).isZero();
        assertThat(afterStockoutCancel).isEqualTo(1);
    }
}
