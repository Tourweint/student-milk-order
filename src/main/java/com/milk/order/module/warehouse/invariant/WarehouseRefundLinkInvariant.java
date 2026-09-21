package com.milk.order.module.warehouse.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_LEDGER_REFUND_LINK：拒收退回与仓库入账一致。
 * —— 真拒收（{@code sign_status = 3 AND reject_reason_code IS NOT NULL}）的签收记录
 * 必须有一条 {@code IN_BACK} 退回入账。
 *
 * <p><b>为什么判据必须带 {@code reject_reason_code}</b>（与 {@code INV_TASK_COMPENSATION} 同源）：
 * 任务作废联动会把未签收记录经 {@code markRecordRejected} 置为 {@code sign_status = 3}
 * （缺货取消、退订都走它），但它们不写原因分类——那类记录**奶根本没被拒收**，
 * 只按 {@code sign_status = 3} 判会把它们误报成"拒收未入账"，进而诱导人去补一条并不存在的退回。</p>
 *
 * <p><b>为什么只告警不自动修</b>：退回是否真的能回仓涉及实物状态（破损、变质），
 * 补账要写的是"这批奶还在、还能卖"这一业务判断，属规则 §0.2 第 5 条红线，只报出来交人工核实。</p>
 */
@Component
@RequiredArgsConstructor
public class WarehouseRefundLinkInvariant implements ProcessInvariant {

    public static final String CODE = "INV_LEDGER_REFUND_LINK";
    private static final String ENTITY_TYPE = "delivery_record";

    private static final String SQL = """
            SELECT r.id AS record_id, r.task_id, r.product_id, r.quantity, r.reject_reason_code,
                   COALESCE(t.task_no, '(任务不存在)') AS task_no
            FROM delivery_record r
            LEFT JOIN delivery_task t ON t.id = r.task_id AND t.deleted = 0
            LEFT JOIN warehouse_ledger l
                   ON l.biz_type = 'IN_BACK' AND l.ref_id = r.id AND l.deleted = 0
            WHERE r.deleted = 0
              AND r.sign_status = 3
              AND r.reject_reason_code IS NOT NULL
              AND l.id IS NULL
            ORDER BY r.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "拒收退回一致：真拒收（有原因分类）的签收记录必须有 IN_BACK 退回入账";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.ALERT_ONLY;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return jdbcTemplate.query(SQL, (rs, rowNum) -> InvariantViolation.builder()
                .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                .entityType(ENTITY_TYPE)
                .entityId(rs.getLong("record_id"))
                .bizNo(rs.getString("task_no"))
                .detail("签收记录 #" + rs.getLong("record_id") + "（任务 " + rs.getString("task_no")
                        + "）已拒收（原因 " + rs.getString("reject_reason_code") + "，"
                        + rs.getInt("quantity") + " 盒），但仓库台账没有对应的 IN_BACK 退回入账——"
                        + "若这批奶确实已带回学校，需人工补记退回（破损不可回卖的走 ADJ 冲销）")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：退回是否可回仓涉实物状态，属人工判断
        return false;
    }
}
