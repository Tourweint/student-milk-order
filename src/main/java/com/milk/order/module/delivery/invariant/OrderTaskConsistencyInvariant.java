package com.milk.order.module.delivery.invariant;

import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_ORDER_TASK：已支付订单的配送任务必须覆盖「品种 × 配送日」的全部组合。
 *
 * <p>比对的是 (配送日期, 品种) 组合数而不是任务行数：任务行数会因为同品种多明细合并、
 * 单期取消等原因与“理论行数”不等，而组合数是业务上真实应当覆盖的网格，
 * 既不会因合并而误报，也不会因取消而误报（取消是状态变更，行仍在）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderTaskConsistencyInvariant implements ProcessInvariant {

    public static final String CODE = "INV_ORDER_TASK";
    private static final String ENTITY_TYPE = "order_info";

    private static final String SQL = """
            SELECT o.id AS order_id, o.order_no,
                   COUNT(DISTINCT CONCAT(t.delivery_date, '#', t.product_id)) AS actual_pairs,
                   (DATEDIFF(o.delivery_end_date, o.delivery_start_date) + 1)
                       * (SELECT COUNT(DISTINCT i.product_id)
                          FROM order_item i
                          WHERE i.order_id = o.id AND i.deleted = 0) AS expected_pairs
            FROM order_info o
            LEFT JOIN delivery_task t ON t.order_id = o.id AND t.deleted = 0
            WHERE o.deleted = 0
              AND o.status IN (2, 3, 4)
              AND o.delivery_start_date IS NOT NULL
              AND o.delivery_end_date IS NOT NULL
            GROUP BY o.id, o.order_no, o.delivery_start_date, o.delivery_end_date
            HAVING actual_pairs < expected_pairs
            ORDER BY o.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryTaskService deliveryTaskService;
    private final OrderInfoMapper orderInfoMapper;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "订单与任务一致：已支付订单的配送任务覆盖「品种 × 配送日」的全部组合";
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
                .entityId(rs.getLong("order_id"))
                .bizNo(rs.getString("order_no"))
                .detail("期望任务网格：" + rs.getInt("expected_pairs") + " 个（品种×配送日）组合；实际："
                        + rs.getInt("actual_pairs") + " 个，缺失 "
                        + (rs.getInt("expected_pairs") - rs.getInt("actual_pairs")) + " 个")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        OrderInfo order = orderInfoMapper.selectById(violation.getEntityId());
        if (order == null || order.getDeliveryStartDate() == null || order.getDeliveryEndDate() == null) {
            return false;
        }
        // 经业务出口补生成（内部按品种合并 + 数据库唯一键仲裁，幂等）
        return deliveryTaskService.generateTasksForOrder(order) > 0;
    }
}
