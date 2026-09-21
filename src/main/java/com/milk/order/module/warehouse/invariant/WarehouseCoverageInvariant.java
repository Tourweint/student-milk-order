package com.milk.order.module.warehouse.invariant;

import com.milk.order.common.constant.QuotaConstants;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 不变量 INV_WAREHOUSE_COVERAGE：发行封顶关系式持续成立。
 *
 * <pre>
 * 每个品种：W ≥ Σ_{池: quota_date ∈ [today−2, today]} GREATEST(total−used, 0)
 *              + Σ_{任务: status=1, delivery_date ≤ today} quantity
 * </pre>
 *
 * <p><b>为什么在闸门之外还要一条不变量：</b>发行封顶是**发行那一刻的闸门**，
 * 只在设配额时生效；而这条关系式还依赖持续变化的两侧——并发绕过（校验-动作竞态）、
 * 规则被临时停用、有人直接改库、以及"发行之后任务才被生成"等路径。
 * 把它登记为可周期求值的检查项，才能回答"现在这套额度还兜得住吗"，
 * 而不是只在发行瞬间回答过一次。</p>
 *
 * <p><b>为什么只告警不自动修</b>：自动修意味着自动下调他人已设的配额 =
 * 用机器替管理员做业务决策（且会让"已售出的额度"与新总额冲突），只能人工核减。</p>
 *
 * <p>判据口径与 {@code WarehouseService.requireIssuanceCoverage} 完全一致
 * （同一份公式、同一个 {@link QuotaConstants#SHELF_DAYS}），两处若不一致，
 * 会出现"闸门放过、体检报错"的长期噪声。</p>
 */
@Component
@RequiredArgsConstructor
public class WarehouseCoverageInvariant implements ProcessInvariant {

    public static final String CODE = "INV_WAREHOUSE_COVERAGE";
    private static final String ENTITY_TYPE = "product";

    /**
     * 外层再套一层子查询过滤：MySQL 的 {@code ONLY_FULL_GROUP_BY} 下，
     * "无 GROUP BY 却用 HAVING 引用别名"不是可移植写法（MariaDB 与 MySQL 表现也不同），
     * 子查询 + 外层 WHERE 在两种数据库上都确定可用。
     */
    private static final String SQL = """
            SELECT * FROM (
                SELECT p.id AS product_id, p.product_name,
                       (SELECT IFNULL(SUM(CASE WHEN l.biz_type IN ('IN', 'IN_BACK', 'INIT') THEN l.quantity
                                               WHEN l.biz_type = 'OUT' THEN -l.quantity
                                               ELSE l.quantity END), 0)
                          FROM warehouse_ledger l
                         WHERE l.deleted = 0 AND l.product_id = p.id) AS w,
                       (SELECT IFNULL(SUM(GREATEST(q.total_quota - IFNULL(q.used_quota, 0), 0)), 0)
                          FROM daily_quota q
                         WHERE q.deleted = 0 AND q.product_id = p.id
                           AND q.quota_date BETWEEN ? AND ?) AS demand_quota,
                       (SELECT IFNULL(SUM(t.quantity), 0)
                          FROM delivery_task t
                         WHERE t.deleted = 0 AND t.status = 1 AND t.product_id = p.id
                           AND t.delivery_date <= ?) AS demand_task
                  FROM product p
                 WHERE p.deleted = 0
            ) x
            WHERE x.w < x.demand_quota + x.demand_task
            ORDER BY x.product_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "发行封顶：每个品种 W ≥ 当日可卖额度 + 待送出任务盒数";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.ALERT_ONLY;
    }

    /**
     * 依赖 {@link WarehouseOutLedgerInvariant#CODE}：补记 OUT 会**降低** W，
     * 因此覆盖不足必须在"出库账补全之后"判定，否则体检会漏报（用虚高的余量去比对需求）。
     */
    @Override
    public List<String> dependsOn() {
        return List.of(WarehouseOutLedgerInvariant.CODE);
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(QuotaConstants.SHELF_DAYS - 1L);
        return jdbcTemplate.query(SQL, (rs, rowNum) -> {
            long productId = rs.getLong("product_id");
            int w = rs.getInt("w");
            int demandQuota = rs.getInt("demand_quota");
            int demandTask = rs.getInt("demand_task");
            return InvariantViolation.builder()
                    .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                    .entityType(ENTITY_TYPE).entityId(productId)
                    .bizNo(rs.getString("product_name"))
                    .detail("仓库余量不足以覆盖已发行额度：「" + rs.getString("product_name")
                            + "」余量 " + w + " 盒 < 实物需求 " + (demandQuota + demandTask)
                            + " 盒（当日可卖额度 " + demandQuota + " + 待送出任务 " + demandTask
                            + "）——额度可能绕过了发行闸门（并发 / 规则停用 / 直接改库），需人工核减配额")
                    .build();
        }, from, today, today, Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：自动下调他人已设的配额＝替管理员做业务决策，且会与"已售出的额度"冲突
        return false;
    }
}
