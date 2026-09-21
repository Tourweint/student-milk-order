package com.milk.order.module.refund.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_REFUND_AMOUNT：订单累计已退金额不得超过实付金额；已退款单必须回填正数退款金额。
 *
 * <p><b>为什么只告警不自动修</b>：涉资金流向，且"修多少、补给谁"需要人工判断，撞自动修复红线
 * （对齐 {@code INV_PAYMENT_CONSISTENCY} 的分级原则）。正常路径下 Σ ≤ pay_amount 由累计制公式结构保证
 * （见 {@code RefundOrderServiceImpl#computeRefundAmount}），本检查是"人工改库/旁路写入"的最后一道防线。</p>
 */
@Component
@RequiredArgsConstructor
public class RefundAmountInvariant implements ProcessInvariant {

    public static final String CODE = "INV_REFUND_AMOUNT";
    private static final String ENTITY_TYPE = "order_info";

    private static final String SQL = """
            SELECT o.id AS order_id, o.order_no, o.pay_amount, o.status AS order_status,
                   COALESCE(SUM(r.refund_amount), 0) AS refunded_amount,
                   COUNT(r.id) AS refund_count,
                   SUM(CASE WHEN r.refund_amount IS NULL OR r.refund_amount <= 0 THEN 1 ELSE 0 END)
                       AS invalid_amount_count
            FROM order_info o
            JOIN refund_order r ON r.order_id = o.id AND r.deleted = 0 AND r.status = 3
            WHERE o.deleted = 0
            GROUP BY o.id, o.order_no, o.pay_amount, o.status
            HAVING refunded_amount > COALESCE(o.pay_amount, 0) OR invalid_amount_count > 0
            ORDER BY o.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "退款金额守恒：订单累计已退金额不得超过实付金额，且已退款单必须回填正数退款金额";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.ALERT_ONLY;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return jdbcTemplate.query(SQL, (rs, rowNum) -> InvariantViolation.builder()
                .code(CODE)
                .severity(InvariantSeverity.ALERT_ONLY)
                .entityType(ENTITY_TYPE)
                .entityId(rs.getLong("order_id"))
                .bizNo(rs.getString("order_no"))
                .detail("期望：累计已退 ≤ 实付 " + rs.getBigDecimal("pay_amount") + " 元，且每张已退款单金额 > 0；"
                        + "实际：累计已退 " + rs.getBigDecimal("refunded_amount") + " 元（已退款单 "
                        + rs.getInt("refund_count") + " 张，其中金额非正数 "
                        + rs.getInt("invalid_amount_count") + " 张）")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：涉资金，不自动修复
        return false;
    }
}
