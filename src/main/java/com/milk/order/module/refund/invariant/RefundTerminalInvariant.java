package com.milk.order.module.refund.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_REFUND_TERMINAL：被退款作废的期次必须保持「已取消」终态。
 *
 * <p>退款域**不落**「退款-任务」明细表（申请快照必然漂移，实时查询即真相），因此关联靠执行时刻写在
 * 任务 remark 上的退款单号建立（{@code DeliveryTaskServiceImpl#cancelTaskForRefund} 写入
 * 「退款作废（RFxxxx）」）。本检查据此发现"被退款作废的任务又被改回非终态"——即人工改库或绕过统一出口
 * 的旁路写入，属于涉已退资金的异常，只告警不自动修。</p>
 */
@Component
@RequiredArgsConstructor
public class RefundTerminalInvariant implements ProcessInvariant {

    public static final String CODE = "INV_REFUND_TERMINAL";
    private static final String ENTITY_TYPE = "delivery_task";

    /** 任务状态：已取消（退款作废后的合法终态） */
    private static final int TASK_CANCELLED = 4;

    private static final String SQL = """
            SELECT t.id AS task_id, t.task_no, t.status AS task_status, t.delivery_date,
                   r.id AS refund_id, r.refund_no, r.order_id, r.order_no
            FROM refund_order r
            JOIN delivery_task t ON t.order_id = r.order_id AND t.deleted = 0
                 AND t.remark LIKE CONCAT('%', r.refund_no, '%')
            WHERE r.deleted = 0
              AND r.status = 3
              AND t.status <> %d
            ORDER BY r.id, t.id
            LIMIT ?
            """
            // 用 replace 而非 formatted：SQL 里的 LIKE CONCAT('%', ...) 含 % 字符，
            // String.format 会把它当格式说明符并抛 UnknownFormatConversionException
            .replace("%d", String.valueOf(TASK_CANCELLED));

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "退款期次终态：被退款作废的配送任务必须保持已取消(4)，不得回退为非终态";
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
                .detail("期望：退款单 " + rs.getString("refund_no") + "（订单 " + rs.getString("order_no")
                        + "）作废的任务保持已取消(4)；实际：任务 #" + rs.getLong("task_id")
                        + "（配送日 " + rs.getString("delivery_date") + "）状态为 " + rs.getInt("task_status")
                        + "，需人工核实")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：已退资金关联的履约历史不自动改（终态保守原则）
        return false;
    }
}
