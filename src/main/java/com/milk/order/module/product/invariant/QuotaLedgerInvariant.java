package com.milk.order.module.product.invariant;

import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 不变量 INV_QUOTA_LEDGER：配额账实一致
 * —— {@code daily_quota.used_quota} 必须等于该日该品种未删除台账行的扣减盒数合计。
 *
 * <p><b>口径要点：</b>台账回补走的是逻辑删除（{@code deleted = 1}），因此求和必须限定
 * {@code deleted = 0}；漏掉这个条件会让所有发生过退订的池子长期误报为不一致。</p>
 *
 * <p><b>修复方向：</b>以台账为准重算已售数。台账是“每一笔占用”的事实记录，而 used_quota 是它的汇总，
 * 汇总错了就按事实重算——这个方向是唯一正确的方向，且重算幂等。</p>
 */
@Component
@RequiredArgsConstructor
public class QuotaLedgerInvariant implements ProcessInvariant {

    public static final String CODE = "INV_QUOTA_LEDGER";
    private static final String ENTITY_TYPE = "daily_quota";

    private static final String SQL = """
            SELECT q.id AS quota_id, q.quota_date, q.product_id, q.used_quota,
                   COALESCE(u.ledger_boxes, 0) AS ledger_boxes
            FROM daily_quota q
            LEFT JOIN (SELECT quota_date, product_id, SUM(boxes) AS ledger_boxes
                       FROM daily_quota_usage
                       WHERE deleted = 0
                       GROUP BY quota_date, product_id) u
                   ON u.quota_date = q.quota_date AND u.product_id = q.product_id
            WHERE q.deleted = 0
              AND q.used_quota <> COALESCE(u.ledger_boxes, 0)
            ORDER BY q.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DailyQuotaService dailyQuotaService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "配额账实一致：daily_quota.used_quota 等于该日该品种未删除台账行的扣减盒数合计";
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
                .entityId(rs.getLong("quota_id"))
                .bizNo(rs.getString("quota_date") + "/品种" + rs.getLong("product_id"))
                .detail("期望（台账合计）：" + rs.getInt("ledger_boxes")
                        + "；实际 used_quota：" + rs.getInt("used_quota"))
                .attributes(Map.of(
                        "ledgerBoxes", rs.getInt("ledger_boxes"),
                        "productId", rs.getLong("product_id"),
                        "quotaDate", rs.getString("quota_date")))
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        Integer ledgerBoxes = violation.intAttr("ledgerBoxes");
        if (ledgerBoxes == null) {
            return false;
        }
        return dailyQuotaService.reconcileUsedQuota(violation.getEntityId(), ledgerBoxes);
    }
}
