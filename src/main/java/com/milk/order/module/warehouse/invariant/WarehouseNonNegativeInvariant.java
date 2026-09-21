package com.milk.order.module.warehouse.invariant;

import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_WAREHOUSE_NONNEG：仓库余量非负。
 * —— 任意时点、每个品种的 {@code W = Σ(IN, IN_BACK, INIT) + Σ(ADJ 带符号) − Σ(OUT) ≥ 0}。
 *
 * <p><b>为什么只告警不自动修</b>：W&lt;0 意味着**账错了**（OUT 多记 / IN 漏记 / ADJ 误操作），
 * 而不是"少了一行可以按既有事实推导出来"。补账的方向不确定——可能该补 IN、也可能该冲销 ADJ——
 * 自动挑一个方向等于凭空造事实，只能人工对账后走反向 ADJ 冲销（台账只增不改）。</p>
 */
@Component
@RequiredArgsConstructor
public class WarehouseNonNegativeInvariant implements ProcessInvariant {

    public static final String CODE = "INV_WAREHOUSE_NONNEG";
    private static final String ENTITY_TYPE = "product";

    private static final String SQL = """
            SELECT product_id,
                   SUM(CASE WHEN biz_type IN ('IN', 'IN_BACK', 'INIT') THEN quantity
                            WHEN biz_type = 'OUT' THEN -quantity
                            ELSE quantity END) AS w
            FROM warehouse_ledger
            WHERE deleted = 0
            GROUP BY product_id
            HAVING w < 0
            ORDER BY product_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "仓库余量非负：任意时点每个品种的 W ≥ 0";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.ALERT_ONLY;
    }

    /**
     * 依赖 {@link WarehouseOutLedgerInvariant#CODE}：OUT 漏记会让 W 虚高，
     * 补记之后才谈得上"余量是否为负"。若先判余量、再补出库，就是用一份不完整的账下结论。
     */
    @Override
    public List<String> dependsOn() {
        return List.of(WarehouseOutLedgerInvariant.CODE);
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return jdbcTemplate.query(SQL, (rs, rowNum) -> InvariantViolation.builder()
                .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                .entityType(ENTITY_TYPE)
                .entityId(rs.getLong("product_id"))
                .bizNo("品种" + rs.getLong("product_id"))
                .detail("仓库余量为负：" + rs.getInt("w") + " 盒（期望 ≥ 0）——"
                        + "意味着送出多记 / 到货漏记 / 修正误操作，需人工对账后走反向 ADJ 冲销，"
                        + "不得直接改历史行（台账只增不改）")
                .build(), Math.max(1, limit));
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // ALERT_ONLY：见类注释——账错的方向不确定，自动补账等于凭空造事实
        return false;
    }
}
