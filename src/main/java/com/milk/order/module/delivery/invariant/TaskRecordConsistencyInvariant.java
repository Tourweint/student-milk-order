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
 * 不变量 INV_TASK_RECORD：任务已完成（3）时，其签收记录必须已签收（1）。
 *
 * <p>正常路径下任务与记录在同一事务内推进，两者不会分叉；一旦分叉，说明存在绕过统一出口的
 * 旁路写入或人工改库——这正是需要被持续监控的场景（该不变量在企业里对应“对账”的一种）。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskRecordConsistencyInvariant implements ProcessInvariant {

    public static final String CODE = "INV_TASK_RECORD";
    private static final String ENTITY_TYPE = "delivery_record";

    /** 任务状态：已完成 */
    private static final int TASK_COMPLETED = 3;
    /** 签收状态：已签收 */
    private static final int SIGN_SIGNED = 1;

    private static final String SQL = """
            SELECT t.id AS task_id, t.task_no, t.order_id, r.id AS record_id, r.sign_status
            FROM delivery_task t
            JOIN delivery_record r ON r.task_id = t.id AND r.deleted = 0
            WHERE t.deleted = 0
              AND t.status = %d
              AND r.sign_status <> %d
            ORDER BY r.id
            LIMIT ?
            """.formatted(TASK_COMPLETED, SIGN_SIGNED);

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryTaskService deliveryTaskService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "任务与签收记录一致：任务已完成时其签收记录必须为已签收";
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
                .bizNo(rs.getString("task_no"))
                .detail("期望：签收记录为已签收(1)；实际：sign_status=" + rs.getInt("sign_status")
                        + "（任务 #" + rs.getLong("task_id") + " 已为已完成(3)，订单 #" + rs.getLong("order_id") + "）")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        return deliveryTaskService.repairRecordSigned(violation.getEntityId());
    }
}
