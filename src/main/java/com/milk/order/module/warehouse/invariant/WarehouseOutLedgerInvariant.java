package com.milk.order.module.warehouse.invariant;

import com.milk.order.module.warehouse.service.WarehouseService;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 不变量 INV_LEDGER_OUT_TASK：出库台账与"已送出"事实一致（两个方向都查）。
 *
 * <p><b>判据为什么是 {@code dispatch_time IS NOT NULL} 而不是任务状态：</b>
 * 任务状态的"已取消(4)"是个多来源终态——缺货取消、家长当日豁免、平移作废、期末摊平
 * **全是 1→4 且从未送出**。若按 {@code status ∈ {2,3,4}} 判"应已出库"，这些任务会被成批
 * 误判为漏记，而处置是 AUTO_REPAIR → 体检自己会**补写出大量假出库行，把余量打成负数**
 * （设计方案 §11 P0-3）。{@code dispatch_time} 与状态迁移在同一个 CAS 里写入，
 * 是"这一刻奶真的离开仓库"的唯一无歧义证据。</p>
 *
 * <p><b>两个方向的处置不同：</b></p>
 * <ul>
 *   <li>已送出却无 OUT 行 → {@code AUTO_REPAIR}：漏记可逆、且由既有事实（dispatch_time）唯一推导，
 *       补写走 {@code uk_biz_ref} 幂等；</li>
 *   <li>有 OUT 行却无送出事实 → {@code ALERT_ONLY}：这说明有人写了一条不该有的出库，
 *       修正方式取决于真实对账结果（余量偏小会让后续配额发行被误拒），属人工判断。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WarehouseOutLedgerInvariant implements ProcessInvariant {

    public static final String CODE = "INV_LEDGER_OUT_TASK";

    /** 方向一的主体是配送任务 */
    private static final String ENTITY_TYPE_TASK = "delivery_task";
    /** 方向二的主体是台账行本身（与方向一用不同 entityType，避免主键空间互相占用） */
    private static final String ENTITY_TYPE_LEDGER = "warehouse_ledger";

    private static final String MISSING_SQL = """
            SELECT t.id AS task_id, t.task_no, t.product_id, t.quantity,
                   DATE_FORMAT(t.dispatch_time, '%Y-%m-%d') AS dispatch_date
            FROM delivery_task t
            LEFT JOIN warehouse_ledger l
                   ON l.biz_type = 'OUT' AND l.ref_id = t.id AND l.deleted = 0
            WHERE t.deleted = 0
              AND t.dispatch_time IS NOT NULL
              AND l.id IS NULL
            ORDER BY t.id
            LIMIT ?
            """;

    private static final String EXTRA_SQL = """
            SELECT l.id AS ledger_id, l.ref_id AS task_id, COALESCE(t.task_no, '(任务不存在)') AS task_no
            FROM warehouse_ledger l
            LEFT JOIN delivery_task t ON t.id = l.ref_id AND t.deleted = 0
            WHERE l.deleted = 0
              AND l.biz_type = 'OUT'
              AND (t.id IS NULL OR t.dispatch_time IS NULL)
            ORDER BY l.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WarehouseService warehouseService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "出库台账一致：已送出（dispatch_time 非空）的任务必有且仅有一条 OUT 出库行";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.AUTO_REPAIR;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        int capped = Math.max(1, limit);
        List<InvariantViolation> violations = new ArrayList<>(jdbcTemplate.query(MISSING_SQL, (rs, rowNum) -> {
            long taskId = rs.getLong("task_id");
            return InvariantViolation.builder()
                    .code(CODE).severity(InvariantSeverity.AUTO_REPAIR)
                    .entityType(ENTITY_TYPE_TASK).entityId(taskId)
                    .bizNo(rs.getString("task_no"))
                    .detail("任务 #" + taskId + "（" + rs.getString("task_no") + "）已于 "
                            + rs.getString("dispatch_date") + " 送出，但仓库台账没有对应的 OUT 行"
                            + "（应出库 " + rs.getInt("quantity") + " 盒）——需补记")
                    .attributes(Map.of(
                            "productId", rs.getLong("product_id"),
                            "quantity", rs.getInt("quantity"),
                            "bizDate", rs.getString("dispatch_date")))
                    .build();
        }, capped));
        violations.addAll(jdbcTemplate.query(EXTRA_SQL, (rs, rowNum) -> InvariantViolation.builder()
                .code(CODE).severity(InvariantSeverity.ALERT_ONLY)
                .entityType(ENTITY_TYPE_LEDGER).entityId(rs.getLong("ledger_id"))
                .bizNo(rs.getString("task_no"))
                .detail("仓库台账 OUT 行 #" + rs.getLong("ledger_id") + " 指向任务 #"
                        + rs.getLong("task_id") + "（" + rs.getString("task_no")
                        + "），但该任务没有送出记录（从未送出或任务不存在）——"
                        + "多做一条出库会让余量偏小、进而让配额发行被误拒，需人工核对后走反向 ADJ 冲销")
                .build(), capped));
        return violations;
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // 只有方向一可修：entityId 即任务 id，biz_date 取实际送出日（与业务日账口径一致）
        if (!ENTITY_TYPE_TASK.equals(violation.getEntityType())) {
            return false;
        }
        Integer productId = violation.intAttr("productId");
        Integer quantity = violation.intAttr("quantity");
        String bizDate = violation.strAttr("bizDate");
        if (productId == null || quantity == null || bizDate == null || violation.getEntityId() == null) {
            return false;
        }
        return warehouseService.recordOutStock(violation.getEntityId(), productId.longValue(),
                LocalDate.parse(bizDate), quantity);
    }
}
