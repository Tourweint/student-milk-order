package com.milk.order.module.order.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 不变量 INV_PAYMENT_CONSISTENCY：订单状态与支付流水必须互证。
 *
 * <p><b>为什么只告警不自动修：</b>这条不变量的两端都可能是“对的”——
 * 可能是订单状态漏落账，也可能是流水本身有问题（重复扣款、金额不符）。
 * 自动改任何一侧都可能掩盖一次真实的资金事故，因此**必须人工核处**。
 * 这也是本项目对“哪些该自动修、哪些不该”的分界线所在。</p>
 *
 * <p><b>假阳性控制：</b>“流水已成功但订单仍待支付”在支付对账任务的正常处理窗口内（最长 5 分钟）
 * 是合法的中间态，因此该方向只检出自成功时间超过 5 分钟仍未落账的订单，
 * 避免把对账任务的正常工作状态误报成不一致。</p>
 */
@Component
@RequiredArgsConstructor
public class PaymentConsistencyInvariant implements ProcessInvariant {

    public static final String CODE = "INV_PAYMENT_CONSISTENCY";
    private static final String ENTITY_TYPE = "order_info";

    /** 对账任务处理窗口（分钟）：窗口内的“流水成功但订单未落账”属于正常中间态 */
    private static final int RECONCILE_GRACE_MINUTES = 5;

    /** 方向一：订单处于已支付及其后续状态，却查不到任何成功流水 */
    private static final String SQL_PAID_WITHOUT_PAYMENT = """
            SELECT o.id AS order_id, o.order_no, o.status
            FROM order_info o
            WHERE o.deleted = 0
              AND o.status IN (2, 3, 4)
              AND NOT EXISTS (SELECT 1 FROM payment_record p
                              WHERE p.order_id = o.id AND p.deleted = 0 AND p.status = 2)
            LIMIT ?
            """;

    /** 方向二：存在成功流水，订单却仍停在待支付（超过对账处理窗口） */
    private static final String SQL_PAYMENT_WITHOUT_PAID_ORDER = """
            SELECT o.id AS order_id, o.order_no, o.status, p.transaction_id, p.pay_time
            FROM order_info o
            JOIN payment_record p ON p.order_id = o.id AND p.deleted = 0 AND p.status = 2
            WHERE o.deleted = 0
              AND o.status = 1
              AND p.pay_time IS NOT NULL
              AND p.pay_time < DATE_SUB(NOW(), INTERVAL %d MINUTE)
            LIMIT ?
            """.formatted(RECONCILE_GRACE_MINUTES);

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "订单状态与支付流水互证：已支付订单必有成功流水，成功流水必已落账到订单";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.ALERT_ONLY;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        int safeLimit = Math.max(1, limit);
        List<InvariantViolation> violations = new ArrayList<>();
        violations.addAll(jdbcTemplate.query(SQL_PAID_WITHOUT_PAYMENT, (rs, rowNum) ->
                InvariantViolation.builder()
                        .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                        .entityType(ENTITY_TYPE)
                        .entityId(rs.getLong("order_id"))
                        .bizNo(rs.getString("order_no"))
                        .detail("期望：存在支付成功流水；实际：一条都没有（订单状态 =" + rs.getInt("status") + "）")
                        .build(), safeLimit));
        violations.addAll(jdbcTemplate.query(SQL_PAYMENT_WITHOUT_PAID_ORDER, (rs, rowNum) ->
                InvariantViolation.builder()
                        .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                        .entityType(ENTITY_TYPE)
                        .entityId(rs.getLong("order_id"))
                        .bizNo(rs.getString("order_no"))
                        .detail("期望：订单已落账（状态 ≥2）；实际：仍为待支付，"
                                + "但流水 " + rs.getString("transaction_id")
                                + " 自 " + rs.getTimestamp("pay_time") + " 起已成功超过 "
                                + RECONCILE_GRACE_MINUTES + " 分钟")
                        .build(), safeLimit));
        return violations;
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // 刻意不实现：资金相关不一致一律交人工
        return false;
    }
}
