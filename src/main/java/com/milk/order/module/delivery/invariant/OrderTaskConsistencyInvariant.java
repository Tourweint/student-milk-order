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
 * 不变量 INV_ORDER_TASK：订单的配送任务必须覆盖「品种 × 配送日」的全部组合。
 *
 * <p>比对的是 (配送日期, 品种) 组合数而不是任务行数：任务行数会因为同品种多明细合并、
 * 单期取消等原因与“理论行数”不等，而组合数是业务上真实应当覆盖的网格，
 * 既不会因合并而误报，也不会因取消而误报（取消是状态变更，行仍在）。</p>
 *
 * <p><b>处置等级按订单状态分</b>（同一条不变量内的等级细分）：</p>
 * <ul>
 *   <li>订单进行中（已支付 / 配送中）：网格缺口必须补回来 → {@code AUTO_REPAIR}；</li>
 *   <li>订单已终态（已完成 / 已退订）：缺口属于"历史被改过"，
 *       **补造历史是错的**——它会给一个已结束的订单造出待配送任务，
 *       反而制造出「已完成订单 + 待配送任务」这种组合异常。因此只报出来让人核 → {@code ALERT_ONLY}。</li>
 * </ul>
 *
 * <p>这条分界消除了一处真实问题：原先不区分状态地补网格，会与「父过程终态守卫」互相打架
 * （先补上、再作废）。现在根因不再产生，组合守卫退化为最后防线。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderTaskConsistencyInvariant implements ProcessInvariant {

    public static final String CODE = "INV_ORDER_TASK";
    private static final String ENTITY_TYPE = "order_info";

    /** 订单状态：已支付 / 配送中（进行中，网格缺口可自动补全） */
    private static final int ORDER_PAID = 2;
    private static final int ORDER_DELIVERING = 3;
    /** 订单状态：已完成 / 已退订（终态，网格缺口只告警、不补造历史） */
    private static final int ORDER_COMPLETED = 4;
    private static final int ORDER_CANCELLED = 5;

    private static final String SQL = """
            SELECT o.id AS order_id, o.order_no, o.status AS order_status,
                   COUNT(DISTINCT CONCAT(t.delivery_date, '#', t.product_id)) AS actual_pairs,
                   (DATEDIFF(o.delivery_end_date, o.delivery_start_date) + 1)
                       * (SELECT COUNT(DISTINCT i.product_id)
                          FROM order_item i
                          WHERE i.order_id = o.id AND i.deleted = 0) AS expected_pairs
            FROM order_info o
            LEFT JOIN delivery_task t ON t.order_id = o.id AND t.deleted = 0
            WHERE o.deleted = 0
              AND o.status IN (2, 3, 4, 5)
              AND o.delivery_start_date IS NOT NULL
              AND o.delivery_end_date IS NOT NULL
            GROUP BY o.id, o.order_no, o.status, o.delivery_start_date, o.delivery_end_date
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
        return jdbcTemplate.query(SQL, (rs, rowNum) -> {
            int orderStatus = rs.getInt("order_status");
            boolean terminal = orderStatus >= ORDER_COMPLETED;
            return InvariantViolation.builder()
                    .code(CODE)
                    // 进行中的订单：网格缺口就补回来；已完成的订单：缺口属于"历史被改过"，
                    // 补造历史是错的（而且会给已完成订单造出一条待配送任务），因此只报出来让人核
                    .severity(terminal ? InvariantSeverity.ALERT_ONLY : InvariantSeverity.AUTO_REPAIR)
                    .entityType(ENTITY_TYPE)
                    .entityId(rs.getLong("order_id"))
                    .bizNo(rs.getString("order_no"))
                    .detail("订单状态 " + orderStatus + statusText(orderStatus)
                            + "，期望任务网格：" + rs.getInt("expected_pairs") + " 个（品种×配送日）组合；实际："
                            + rs.getInt("actual_pairs") + " 个，缺失 "
                            + (rs.getInt("expected_pairs") - rs.getInt("actual_pairs")) + " 个")
                    .build();
        }, Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        OrderInfo order = orderInfoMapper.selectById(violation.getEntityId());
        if (order == null || order.getDeliveryStartDate() == null || order.getDeliveryEndDate() == null) {
            return false;
        }
        // 终态订单的缺口只告警不修（探测阶段已降级为 ALERT_ONLY，正常不会被调用到这里）；
        // 这里再兜一层，避免调用方绕过等级直接调用修复时给已完成的订单补造配送任务
        if (order.getStatus() != null && order.getStatus() >= ORDER_COMPLETED) {
            return false;
        }
        // 经业务出口补生成（内部按品种合并 + 数据库唯一键仲裁，幂等）
        return deliveryTaskService.generateTasksForOrder(order) > 0;
    }

    private String statusText(int status) {
        return switch (status) {
            case ORDER_PAID -> "（已支付）";
            case ORDER_DELIVERING -> "（配送中）";
            case ORDER_COMPLETED -> "（已完成）";
            case ORDER_CANCELLED -> "（已退订）";
            default -> "";
        };
    }
}
