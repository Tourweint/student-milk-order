package com.milk.order.process.reconcile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过程补偿协调器：把「探测 → 决策 → 执行」串成一次补偿，是补偿能力的统一入口。
 *
 * <p>两条通道共用本入口，但失败策略不同（这是刻意的设计，不是不一致）：</p>
 * <ul>
 *   <li><b>兜底通道</b>（{@link #reconcile}）：一轮扫一批，单条失败只记录并继续——
 *       目标是「本轮尽可能多修」，不能让一条脏数据挡住其余。</li>
 *   <li><b>实时通道</b>（{@link #reconcileEntity}）：单条，异常向上抛——
 *       目标是「失败必须被上层看见并重试」，因此不吞异常。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessReconcileCoordinator {

    private final ProcessReconcileEngine engine;
    private final List<ParentProcessAggregator> aggregators;

    /** 场景 → 聚合器索引（首次使用时构建） */
    private volatile Map<String, ParentProcessAggregator> aggregatorIndex;

    /** 批量探测（只读，不执行）：供不变量体检与管理端预览使用 */
    public List<ReconcileDecision> detect(String scene, int limit) {
        return engine.detect(scene, limit);
    }

    /** 单主体探测（只读，不执行） */
    public List<ReconcileDecision> detectEntity(String scene, Long entityId) {
        return engine.detectEntity(scene, entityId);
    }

    /**
     * 批量补偿（兜底通道）：探测 + 逐条执行，单条失败不影响其余。
     *
     * @return 每条决策的执行结果（含“未生效”与“异常”两类未修复）
     */
    public List<ReconcileOutcome> reconcile(String scene, int limit) {
        List<ReconcileDecision> decisions = engine.detect(scene, limit);
        List<ReconcileOutcome> outcomes = new ArrayList<>(decisions.size());
        for (ReconcileDecision decision : decisions) {
            try {
                outcomes.add(apply(decision));
            } catch (Exception e) {
                log.warn("[过程补偿] 主体 {} 补偿异常，本轮跳过：{}",
                        decision.getCandidate().getId(), e.getMessage());
                outcomes.add(ReconcileOutcome.builder()
                        .entityId(decision.getCandidate().getId())
                        .bizNo(decision.getCandidate().getBizNo())
                        .ruleName(decision.getRule().getName())
                        .action(decision.getRule().getAction())
                        .fromStatus(decision.getCandidate().getStatus())
                        .toStatus(decision.getRule().getTargetStatus())
                        .repaired(false).failed(true)
                        .message(e.getMessage())
                        .build());
            }
        }
        return outcomes;
    }

    /**
     * 单主体补偿（实时通道）：按最新状态重新探测并执行；未发现漂移视为「已收敛」，不抛异常。
     *
     * <p>重新探测而不是复用入队时的判定：待办从入队到被消费之间父状态可能已自行推进
     * （例如同步快路径已经修好了），此时补偿应当什么都不做。</p>
     */
    public ReconcileOutcome reconcileEntity(String scene, Long entityId) {
        List<ReconcileDecision> decisions = engine.detectEntity(scene, entityId);
        if (decisions.isEmpty()) {
            return ReconcileOutcome.noDrift(entityId, null);
        }
        return apply(decisions.get(0));
    }

    private ReconcileOutcome apply(ReconcileDecision decision) {
        ProcessReconcileRule rule = decision.getRule();
        ProcessCandidate candidate = decision.getCandidate();
        ParentProcessAggregator aggregator = aggregatorIndex().get(rule.getParentScene());
        if (aggregator == null) {
            throw new IllegalStateException("未注册场景 " + rule.getParentScene() + " 的父过程聚合器，无法补偿");
        }
        boolean repaired = aggregator.compensate(rule, candidate);
        if (repaired) {
            log.info("[过程补偿] {} #{} 命中规则「{}」：{} → {}（补偿生效）",
                    rule.getParentScene(), candidate.getId(), rule.getName(),
                    candidate.getStatus(), rule.getTargetStatus());
        } else {
            log.debug("[过程补偿] {} #{} 命中规则「{}」但未生效（并发已推进或规则表禁止）",
                    rule.getParentScene(), candidate.getId(), rule.getName());
        }
        return ReconcileOutcome.builder()
                .entityId(candidate.getId())
                .bizNo(candidate.getBizNo())
                .ruleName(rule.getName())
                .action(rule.getAction())
                .fromStatus(candidate.getStatus())
                .toStatus(rule.getTargetStatus())
                .repaired(repaired)
                .failed(false)
                .message(repaired ? "补偿生效" : "补偿未生效（状态已被并发变更或迁移规则禁止）")
                .build();
    }

    private Map<String, ParentProcessAggregator> aggregatorIndex() {
        Map<String, ParentProcessAggregator> index = aggregatorIndex;
        if (index == null) {
            synchronized (this) {
                index = aggregatorIndex;
                if (index == null) {
                    Map<String, ParentProcessAggregator> map = new LinkedHashMap<>();
                    for (ParentProcessAggregator aggregator : aggregators) {
                        ParentProcessAggregator previous = map.put(aggregator.scene(), aggregator);
                        if (previous != null) {
                            throw new IllegalStateException("同一场景注册了多个父过程聚合器：" + aggregator.scene());
                        }
                    }
                    index = Map.copyOf(map);
                    aggregatorIndex = index;
                    log.info("[过程补偿] 已注册父过程聚合器：{}", map.keySet());
                }
            }
        }
        return index;
    }
}
