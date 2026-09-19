package com.milk.order.module.delivery.invariant;

import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_SIGN_INTAKE：已签收的配送记录必须有对应的营养摄入记录。
 *
 * <p>营养摄入是签收的派生产物（同事务写入）。这条不变量保证「计划值」与「实际摄入」不脱节——
 * 缺一条就会让营养统计少算一份奶，而且这种缺失在界面上完全看不出来。</p>
 */
@Component
@RequiredArgsConstructor
public class SignIntakeConsistencyInvariant implements ProcessInvariant {

    public static final String CODE = "INV_SIGN_INTAKE";
    private static final String ENTITY_TYPE = "delivery_record";

    /** 签收状态：已签收 */
    private static final int SIGN_SIGNED = 1;

    private static final String SQL = """
            SELECT r.id AS record_id, r.task_id, r.student_id, r.product_id, r.quantity
            FROM delivery_record r
            LEFT JOIN nutrition_intake n ON n.delivery_record_id = r.id AND n.deleted = 0
            WHERE r.deleted = 0
              AND r.sign_status = %d
              AND n.id IS NULL
            ORDER BY r.id
            LIMIT ?
            """.formatted(SIGN_SIGNED);

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryTaskService deliveryTaskService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "签收与营养一致：已签收的配送记录必须存在对应的营养摄入记录";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.AUTO_REPAIR;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return jdbcTemplate.query(SQL, (rs, rowNum) -> InvariantViolation.builder()
                .code(CODE).severity(InvariantSeverity.AUTO_REPAIR)
                .entityType(ENTITY_TYPE)
                .entityId(rs.getLong("record_id"))
                .bizNo("记录#" + rs.getLong("record_id"))
                .detail("期望：存在营养摄入记录；实际：一条都没有（学生 #" + rs.getLong("student_id")
                        + "，品种 #" + rs.getLong("product_id") + "，数量 " + rs.getInt("quantity") + "）")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        return deliveryTaskService.repairIntakeForRecord(violation.getEntityId());
    }
}
