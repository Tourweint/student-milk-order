package com.milk.order.process.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.process.mapper.ProcessReconcileRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 补偿探测器实现：规则表驱动的漂移探测。
 *
 * <p>依赖方向：过程层 → { 补偿规则表，各业务模块注册的探测器 }。
 * 过程层不认识任何业务表，因此新增父过程类型时本类无需改动。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessReconcileEngineImpl implements ProcessReconcileEngine {

    private final ProcessReconcileRuleMapper ruleMapper;
    private final List<ParentProcessProbe> probes;

    /** 场景 → 探测器索引（首次使用时构建，避免依赖容器初始化顺序） */
    private volatile Map<String, ParentProcessProbe> probeIndex;

    @Override
    public List<ReconcileDecision> detect(String parentScene, int limit) {
        ParentProcessProbe probe = requireProbe(parentScene);
        List<ProcessReconcileRule> rules = enabledRules(parentScene);
        if (rules.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        // 按父状态分组：同一状态只扫一次候选表（规则条数不影响查询次数）
        Map<Integer, List<ProcessReconcileRule>> rulesByStatus = new LinkedHashMap<>();
        for (ProcessReconcileRule rule : rules) {
            rulesByStatus.computeIfAbsent(rule.getParentStatus(), k -> new ArrayList<>()).add(rule);
        }
        List<ReconcileDecision> decisions = new ArrayList<>();
        for (Map.Entry<Integer, List<ProcessReconcileRule>> entry : rulesByStatus.entrySet()) {
            List<ProcessCandidate> candidates = probe.candidatesByStatus(entry.getKey(), safeLimit);
            for (ProcessCandidate candidate : candidates) {
                ProcessReconcileRule hit = firstMatch(probe, candidate, entry.getValue());
                if (hit != null) {
                    decisions.add(ReconcileDecision.builder().rule(hit).candidate(candidate).build());
                }
            }
        }
        return decisions;
    }

    @Override
    public List<ReconcileDecision> detectEntity(String parentScene, Long parentId) {
        ParentProcessProbe probe = requireProbe(parentScene);
        ProcessCandidate candidate = probe.find(parentId);
        if (candidate == null) {
            return List.of();
        }
        List<ProcessReconcileRule> rules = enabledRules(parentScene).stream()
                .filter(rule -> Objects.equals(rule.getParentStatus(), candidate.getStatus()))
                .toList();
        ProcessReconcileRule hit = firstMatch(probe, candidate, rules);
        return hit == null ? List.of()
                : List.of(ReconcileDecision.builder().rule(hit).candidate(candidate).build());
    }

    /**
     * 取首个命中的规则（规则按 id 升序，顺序稳定且可预测）。
     *
     * <p>同一主体一轮只补偿一次：若允许叠加，一次探测就可能把父过程连跳两个状态，
     * 使补偿范围超出单条规则的可审阅边界。</p>
     */
    private ProcessReconcileRule firstMatch(ParentProcessProbe probe, ProcessCandidate candidate,
                                            List<ProcessReconcileRule> rules) {
        for (ProcessReconcileRule rule : rules) {
            ChildProcessCondition condition = parseCondition(rule);
            if (probe.evaluateChildCondition(condition, candidate.getId())) {
                return rule;
            }
        }
        return null;
    }

    private ChildProcessCondition parseCondition(ProcessReconcileRule rule) {
        try {
            return ChildProcessCondition.fromCode(rule.getChildCondition());
        } catch (IllegalArgumentException e) {
            // 规则表配错必须快速失败：静默跳过会让漂移永远修不掉，且无从发现
            throw new IllegalStateException("补偿规则配置错误（规则 " + rule.getId() + "「" + rule.getName()
                    + "」）：" + e.getMessage(), e);
        }
    }

    private List<ProcessReconcileRule> enabledRules(String parentScene) {
        return ruleMapper.selectList(new LambdaQueryWrapper<ProcessReconcileRule>()
                .eq(ProcessReconcileRule::getParentScene, parentScene)
                .eq(ProcessReconcileRule::getEnabled, 1)
                .orderByAsc(ProcessReconcileRule::getId));
    }

    private ParentProcessProbe requireProbe(String parentScene) {
        ParentProcessProbe probe = probeIndex().get(parentScene);
        if (probe == null) {
            throw new IllegalStateException("未注册场景 " + parentScene + " 的父过程探测器，无法探测漂移");
        }
        return probe;
    }

    private Map<String, ParentProcessProbe> probeIndex() {
        Map<String, ParentProcessProbe> index = probeIndex;
        if (index == null) {
            synchronized (this) {
                index = probeIndex;
                if (index == null) {
                    Map<String, ParentProcessProbe> map = new LinkedHashMap<>();
                    for (ParentProcessProbe probe : probes) {
                        ParentProcessProbe previous = map.put(probe.scene(), probe);
                        if (previous != null) {
                            throw new IllegalStateException("同一场景注册了多个父过程探测器：" + probe.scene());
                        }
                    }
                    index = Map.copyOf(map);
                    probeIndex = index;
                    log.info("[过程补偿] 已注册父过程探测器：{}", map.keySet());
                }
            }
        }
        return index;
    }
}
