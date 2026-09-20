package com.milk.order.module.delivery.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_TASK_COMPENSATION：真拒收的任务必须存在一条补送补偿台账记录。
 *
 * <p><b>为什么只告警不自动修</b>：补送缺失的自动修复要写配额台账（资金性质）并判断"次日是哪一天、
 * 该不该补"（业务判断），撞规则 §0.2 第 5 条红线，因此只报出来交人工核实。</p>
 *
 * <p><b>判定"真拒收"必须用 {@code sign_status=3 AND reject_reason_code IS NOT NULL}</b>：
 * 退订联动与缺货取消也会经 {@code markRecordRejected} 把记录置为 {@code sign_status=3}，
 * 但它们不写 {@code reject_reason_code}；只按 {@code sign_status=3} 判会把这两类误报成"拒收未补送"。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskCompensationInvariant implements ProcessInvariant {

    public static final String CODE = "INV_TASK_COMPENSATION";
    private static final String ENTITY_TYPE = "delivery_task";

    private static final String SQL = """
            SELECT t.id AS task_id, t.task_no, t.order_id, t.delivery_date, r.id AS record_id
            FROM delivery_task t
            JOIN delivery_record r ON r.task_id = t.id AND r.deleted = 0
            LEFT JOIN delivery_compensation c ON c.source_task_id = t.id AND c.deleted = 0
            WHERE t.deleted = 0
              AND r.sign_status = 3
              AND r.reject_reason_code IS NOT NULL
              AND c.id IS NULL
            ORDER BY t.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "拒收补送一致：真拒收的任务必须存在补送补偿台账记录";
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
                .entityId(rs.getLong("task_id"))
                .bizNo(rs.getString("task_no"))
                .detail("任务 #" + rs.getLong("task_id") + "（" + rs.getString("task_no")
                        + "，配送日 " + rs.getString("delivery_date") + "）已被拒收（记录 #"
                        + rs.getLong("record_id") + "）但无 delivery_compensation 补偿记录，需人工核实补送")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：不自动修复（补送涉及配额与业务判断）
        return false;
    }
}
