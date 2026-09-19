package com.milk.order.process.invariant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.milk.order.process.mapper.ProcessInvariantViolationMapper;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不变量体检扫描器：把「注册的不变量」求值为一份可读报告，并驱动可自动修复项的修复。
 *
 * <p>一轮体检对每条不变量做四件事：</p>
 * <ol>
 *   <li><b>探测</b>（只读）：调用 {@link ProcessInvariant#detect}；</li>
 *   <li><b>记录</b>：新检出则开记录，已闭环后再次漂移则重开并累加重复漂移次数，仍在漂移则刷新明细；</li>
 *   <li><b>修复</b>：仅对 AUTO_REPAIR 项逐条修复；修复失败保持未闭环，由下一轮重试并留下失败原因；</li>
 *   <li><b>复检闭环</b>：上轮未闭环、本轮未再检出的记录自动闭环（问题真的消失了，而不是被忽略）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessInvariantScanner {

    /** 未闭环 */
    public static final int STATUS_OPEN = 0;
    /** 已闭环 */
    public static final int STATUS_CLOSED = 1;

    private final List<ProcessInvariant> invariants;
    private final ProcessInvariantViolationMapper violationMapper;
    private final IdempotencyGuard idempotencyGuard;

    /**
     * 执行一轮体检。
     *
     * @param repair 是否对 AUTO_REPAIR 项执行修复（false 时仅探测与记录，用于预览与实验）
     * @param limit  每项最多检出条数
     */
    public InvariantScanReport scan(boolean repair, int limit) {
        List<InvariantScanResult> results = new ArrayList<>(invariants.size());
        for (ProcessInvariant invariant : orderedInvariants()) {
            results.add(scanOne(invariant, repair, limit));
        }
        InvariantScanReport report = InvariantScanReport.of(results);
        if (report.isAllPassed()) {
            log.info("[不变量体检] {} 类不变量全部通过", invariants.size());
        } else {
            log.warn("[不变量体检] 检出 {} 项不变量违规（自动修复 {}，未闭环 {}）",
                    report.getTotalDetected(), report.getTotalRepaired(), report.getTotalOpen());
        }
        return report;
    }

    private InvariantScanResult scanOne(ProcessInvariant invariant, boolean repair, int limit) {
        String code = invariant.code();
        List<InvariantViolation> detected;
        try {
            detected = invariant.detect(limit);
        } catch (Exception e) {
            // 探测异常 ≠ 不变量成立：必须显式报告“本轮未得出可信结论”，不能静默当作通过
            log.error("[不变量体检] {} 探测异常", code, e);
            return InvariantScanResult.builder()
                    .code(code).description(invariant.description()).severity(invariant.severity())
                    .error(e.getMessage())
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, ProcessInvariantViolation> existing = currentRecords(code);
        Set<Long> detectedIds = new HashSet<>();
        int opened = 0;
        int reopened = 0;
        int repaired = 0;
        int unrepaired = 0;
        int alerts = 0;

        for (InvariantViolation violation : detected) {
            detectedIds.add(violation.getEntityId());
            InvariantSeverity effective = effectiveSeverity(invariant, violation);
            ProcessInvariantViolation record = existing.get(violation.getEntityId());
            if (record == null) {
                insertOpen(violation, effective, now);
                opened++;
            } else if (!Integer.valueOf(STATUS_OPEN).equals(record.getStatus())) {
                reopen(record, violation, effective, now);
                reopened++;
            } else {
                refresh(record.getId(), violation, effective, now);
            }

            // 是否自动修复由**每条违规的实际等级**决定：同一条不变量下，
            // 有的情况可逆可自动修，有的情况（如奶已出库在途）只能告警
            if (!repair) {
                continue;
            }
            if (effective != InvariantSeverity.AUTO_REPAIR) {
                alerts++;
                continue;
            }
            try {
                if (invariant.repair(violation)) {
                    repaired++;
                    markClosed(code, violation.getEntityId(), "已自动修复：" + invariant.description());
                } else {
                    unrepaired++;
                    markRepairFailed(code, violation.getEntityId(), "自动修复未生效（前置条件已不满足）");
                }
            } catch (Exception e) {
                unrepaired++;
                markRepairFailed(code, violation.getEntityId(), "修复异常：" + shorten(e.getMessage()));
                log.warn("[不变量体检] {} 修复主体 {} 失败：{}", code, violation.getEntityId(), e.getMessage());
            }
        }

        int closed = 0;
        for (ProcessInvariantViolation record : existing.values()) {
            if (Integer.valueOf(STATUS_OPEN).equals(record.getStatus())
                    && !detectedIds.contains(record.getEntityId())) {
                markClosed(code, record.getEntityId(), "复检通过（本轮体检未再检出）");
                closed++;
            }
        }

        return InvariantScanResult.builder()
                .code(code).description(invariant.description()).severity(invariant.severity())
                .detected(detected.size()).opened(opened).reopened(reopened)
                .repaired(repaired).unrepaired(unrepaired).alerts(alerts).closed(closed)
                .build();
    }

    /**
     * 计算各项不变量的执行顺序：**按声明依赖的拓扑序**，同层内按编码升序（因此结果确定且可复现）。
     *
     * <p>不变量之间会相互影响（例如「任务网格补齐」会新增一条待配送子任务，
     * 而「父过程终态守卫」正是要发现它），所以顺序必须由声明决定，而不是靠编码字典序碰巧排对。
     * 依赖引用不存在的编码、或依赖成环时**直接失败**——这两种情况都属于配置错误，
     * 静默退化成任意顺序只会让体检结果变得不可解释。</p>
     */
    private List<ProcessInvariant> orderedInvariants() {
        // 先按编码排序：它同时充当"重复编码校验"和拓扑同层的稳定打破平局规则
        List<ProcessInvariant> all = invariants.stream()
                .sorted(Comparator.comparing(ProcessInvariant::code))
                .toList();
        Set<String> codes = new HashSet<>();
        for (ProcessInvariant invariant : all) {
            if (!codes.add(invariant.code())) {
                throw new IllegalStateException("不变量编码重复：" + invariant.code());
            }
        }
        for (ProcessInvariant invariant : all) {
            for (String dependency : invariant.dependsOn()) {
                if (!codes.contains(dependency)) {
                    throw new IllegalStateException("不变量 " + invariant.code()
                            + " 依赖了未注册的编码 " + dependency);
                }
            }
        }

        List<ProcessInvariant> ordered = new ArrayList<>(all.size());
        Set<String> emitted = new HashSet<>();
        while (ordered.size() < all.size()) {
            ProcessInvariant next = all.stream()
                    .filter(invariant -> !emitted.contains(invariant.code()))
                    .filter(invariant -> emitted.containsAll(invariant.dependsOn()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "不变量依赖存在环路，无法确定执行顺序：剩余 " + remainingCodes(all, emitted)));
            ordered.add(next);
            emitted.add(next.code());
        }
        return ordered;
    }

    /**
     * 当前解析出的执行顺序（编码列表）。
     *
     * <p>暴露出来是为了让"顺序"可被测试与人工审阅——顺序本身是设计的一部分，
     * 不应该只存在于一次运行的日志里。</p>
     */
    public List<String> resolvedExecutionOrder() {
        return orderedInvariants().stream().map(ProcessInvariant::code).toList();
    }

    private List<String> remainingCodes(List<ProcessInvariant> all, Set<String> emitted) {
        return all.stream()
                .map(ProcessInvariant::code)
                .filter(code -> !emitted.contains(code))
                .toList();
    }

    /** 读取某不变量的全部记录（每个主体一行），用于判断“新开 / 重开 / 仍在漂移” */
    private Map<Long, ProcessInvariantViolation> currentRecords(String code) {
        List<ProcessInvariantViolation> records = violationMapper.selectList(
                new LambdaQueryWrapper<ProcessInvariantViolation>()
                        .eq(ProcessInvariantViolation::getInvariantCode, code));
        Map<Long, ProcessInvariantViolation> map = new HashMap<>(records.size());
        for (ProcessInvariantViolation record : records) {
            map.put(record.getEntityId(), record);
        }
        return map;
    }

    /**
     * 取该条违规的实际处置等级：以违规自带的等级为准，未声明时回退到不变量的默认等级。
     *
     * <p>回退方向是「不自动修」——等级拿不准时宁可让人看，也不要自动改数据。</p>
     */
    private InvariantSeverity effectiveSeverity(ProcessInvariant invariant, InvariantViolation violation) {
        return violation.getSeverity() == null ? invariant.severity() : violation.getSeverity();
    }

    private void insertOpen(InvariantViolation violation, InvariantSeverity severity, LocalDateTime now) {
        ProcessInvariantViolation record = new ProcessInvariantViolation();
        record.setInvariantCode(violation.getCode());
        record.setSeverity(severity.name());
        record.setEntityType(violation.getEntityType());
        record.setEntityId(violation.getEntityId());
        record.setBizNo(violation.getBizNo());
        record.setDetail(violation.getDetail());
        record.setStatus(STATUS_OPEN);
        record.setReopenCount(0);
        record.setDetectedTime(now);
        // 唯一键 uk_violation(invariant_code, entity_type, entity_id) 仲裁：多实例同时体检时，
        // 两个实例可能都判到"该主体还没记录"而同时插入，冲突方按"已存在"跳过即可——
        // 让数据库承担并发仲裁，而不是靠"先查再插"（同实验三暴露的那类缺陷）
        idempotencyGuard.insertIgnoringDuplicate(() -> violationMapper.insert(record));
    }

    private void reopen(ProcessInvariantViolation record, InvariantViolation violation,
                        InvariantSeverity severity, LocalDateTime now) {
        violationMapper.update(null, new LambdaUpdateWrapper<ProcessInvariantViolation>()
                .eq(ProcessInvariantViolation::getId, record.getId())
                .set(ProcessInvariantViolation::getStatus, STATUS_OPEN)
                .set(ProcessInvariantViolation::getReopenCount,
                        (record.getReopenCount() == null ? 0 : record.getReopenCount()) + 1)
                .set(ProcessInvariantViolation::getSeverity, severity.name())
                .set(ProcessInvariantViolation::getDetail, violation.getDetail())
                .set(ProcessInvariantViolation::getBizNo, violation.getBizNo())
                .set(ProcessInvariantViolation::getDetectedTime, now)
                .set(ProcessInvariantViolation::getHandledTime, null)
                .set(ProcessInvariantViolation::getRepairAction, null));
    }

    private void refresh(Long id, InvariantViolation violation, InvariantSeverity severity, LocalDateTime now) {
        // 等级随本轮实际情况刷新：同一主体的违规可能从「待配送（可自动修）」演变为「配送中（只能告警）」
        violationMapper.update(null, new LambdaUpdateWrapper<ProcessInvariantViolation>()
                .eq(ProcessInvariantViolation::getId, id)
                .set(ProcessInvariantViolation::getSeverity, severity.name())
                .set(ProcessInvariantViolation::getDetail, violation.getDetail())
                .set(ProcessInvariantViolation::getBizNo, violation.getBizNo())
                .set(ProcessInvariantViolation::getDetectedTime, now));
    }

    private void markClosed(String code, Long entityId, String action) {
        violationMapper.update(null, new LambdaUpdateWrapper<ProcessInvariantViolation>()
                .eq(ProcessInvariantViolation::getInvariantCode, code)
                .eq(ProcessInvariantViolation::getEntityId, entityId)
                .eq(ProcessInvariantViolation::getStatus, STATUS_OPEN)
                .set(ProcessInvariantViolation::getStatus, STATUS_CLOSED)
                .set(ProcessInvariantViolation::getRepairAction, shorten(action))
                .set(ProcessInvariantViolation::getHandledTime, LocalDateTime.now()));
    }

    private void markRepairFailed(String code, Long entityId, String action) {
        violationMapper.update(null, new LambdaUpdateWrapper<ProcessInvariantViolation>()
                .eq(ProcessInvariantViolation::getInvariantCode, code)
                .eq(ProcessInvariantViolation::getEntityId, entityId)
                .eq(ProcessInvariantViolation::getStatus, STATUS_OPEN)
                .set(ProcessInvariantViolation::getRepairAction, shorten(action)));
    }

    private String shorten(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 64 ? text.substring(0, 64) : text;
    }
}
