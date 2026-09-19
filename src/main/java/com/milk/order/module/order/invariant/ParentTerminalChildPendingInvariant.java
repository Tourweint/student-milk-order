package com.milk.order.module.order.invariant;

import com.milk.order.module.delivery.invariant.OrderTaskConsistencyInvariant;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 不变量 INV_PARENT_TERMINAL_CHILD_PENDING：父过程已到终态时，不应再存在非终态子过程。
 *
 * <p><b>为什么需要它（它来自一次实验发现）</b>：各条不变量单独看都成立，但修复动作之间没有顺序约束——
 * 「任务网格补齐」可能发生在「父过程聚合」之后，于是得到「已完成订单 + 待配送任务」这种
 * <b>组合可疑</b>状态：没有任何一条已有不变量被违反，但业务上说不通。
 * 这条组合不变量把该状态变成可见、可收敛的。</p>
 *
 * <p><b>处置等级按具体情况下分</b>（同一条不变量内允许不同等级，见 {@link ProcessInvariant#severity()}）：</p>
 * <ul>
 *   <li>残余任务处于「待配送」：奶尚未出库，自动作废是安全的 → {@link InvariantSeverity#AUTO_REPAIR}；
 *       修复经业务取消出口执行（含签收记录作废、迁移台账留痕、自愈待办入队）；</li>
 *   <li>残余任务处于「配送中」：奶已出库在途，自动作废等于把已送出的奶从记录里抹掉，
 *       必须人工处理 → {@link InvariantSeverity#ALERT_ONLY}。
 *       （这一分界与「缺货批量取消只取消待配送任务」的口径一致。）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ParentTerminalChildPendingInvariant implements ProcessInvariant {

    public static final String CODE = "INV_PARENT_TERMINAL_CHILD_PENDING";
    private static final String ENTITY_TYPE = "delivery_task";

    /** 父过程终态：已完成 */
    private static final int ORDER_COMPLETED = 4;
    /** 父过程终态：已退订 */
    private static final int ORDER_CANCELLED = 5;
    /** 子任务：待配送（可安全自动作废） */
    private static final int TASK_PENDING = 1;
    /** 子任务：配送中（奶已出库，只能告警） */
    private static final int TASK_DELIVERING = 2;

    private static final String SQL = """
            SELECT o.id AS order_id, o.order_no, o.status AS order_status,
                   t.id AS task_id, t.task_no, t.status AS task_status, t.delivery_date
            FROM order_info o
            JOIN delivery_task t ON t.order_id = o.id AND t.deleted = 0
            WHERE o.deleted = 0
              AND o.status IN (%d, %d)
              AND t.status IN (%d, %d)
            ORDER BY t.id
            LIMIT ?
            """.formatted(ORDER_COMPLETED, ORDER_CANCELLED, TASK_PENDING, TASK_DELIVERING);

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryTaskService deliveryTaskService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "父过程终态一致：订单已完成/已退订时，不应再存在待配送或配送中的子任务";
    }

    @Override
    public InvariantSeverity severity() {
        // 默认等级取可修复的一种；「配送中」的违规在探测阶段逐条降级为仅告警
        return InvariantSeverity.AUTO_REPAIR;
    }

    /**
     * 组合守卫必须**最后执行**：它要判的是「最终的子任务集合与父状态是否自洽」，
     * 因此必须排在所有可能改变子任务集合（补网格、聚合）的检查项之后，
     * 否则它可能对"中间态"作出判断，甚至与随后的补全动作互相打架。
     */
    @Override
    public List<String> dependsOn() {
        return List.of(OrderTaskConsistencyInvariant.CODE, OrderAggregationInvariant.CODE);
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return jdbcTemplate.query(SQL, (rs, rowNum) -> {
            int taskStatus = rs.getInt("task_status");
            int orderStatus = rs.getInt("order_status");
            boolean delivering = taskStatus == TASK_DELIVERING;
            return InvariantViolation.builder()
                    .code(CODE)
                    .severity(delivering ? InvariantSeverity.ALERT_ONLY : InvariantSeverity.AUTO_REPAIR)
                    .entityType(ENTITY_TYPE)
                    .entityId(rs.getLong("task_id"))
                    .bizNo(rs.getString("task_no"))
                    .detail("父订单 " + rs.getString("order_no") + "（" + orderStatusText(orderStatus)
                            + "）仍存在" + taskStatusText(taskStatus) + "的子任务"
                            + "（配送日期 " + rs.getString("delivery_date") + "）")
                    .attributes(Map.of("orderId", rs.getLong("order_id"), "taskStatus", taskStatus))
                    .build();
        }, Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // 只有「待配送」的残余任务会走到这里（配送中的已在探测阶段降级为仅告警）。
        // 复用业务取消出口：任务 待配送→已取消、未签收记录作废、迁移台账留痕、自愈待办入队，
        // 因此修复本身也是可追溯的，而不是绕过可靠性层的旁路。
        deliveryTaskService.cancelTask(violation.getEntityId(),
                "父订单已终态（不变量体检），作废残余待配送任务");
        return true;
    }

    private String orderStatusText(int status) {
        return switch (status) {
            case ORDER_COMPLETED -> "4 已完成";
            case ORDER_CANCELLED -> "5 已退订";
            default -> String.valueOf(status);
        };
    }

    private String taskStatusText(int status) {
        return switch (status) {
            case TASK_PENDING -> "待配送";
            case TASK_DELIVERING -> "配送中";
            default -> "状态" + status;
        };
    }
}
