package com.milk.order.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.system.entity.StateTransitionRule;
import com.milk.order.module.system.mapper.StateTransitionRuleMapper;
import com.milk.order.module.system.service.StateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 状态机规则实现：规则存于 state_transition_rule 表，全量加载进内存缓存；
 * 未配置的组合默认禁止（白名单语义），管理端修改后缓存立即刷新
 */
@Slf4j
@Service
public class StateMachineServiceImpl extends ServiceImpl<StateTransitionRuleMapper, StateTransitionRule> implements StateMachineService {

    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 60_000L;

    /** key = scene|action|fromStatus → 是否允许 */
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastLoadTime = new AtomicLong(0);

    @Override
    public boolean allowed(String scene, String action, Integer fromStatus) {
        if (scene == null || action == null || fromStatus == null) {
            return false;
        }
        refreshIfStale();
        Boolean value = cache.get(key(scene, action, fromStatus));
        return Boolean.TRUE.equals(value);
    }

    @Override
    public void assertAllowed(String scene, String action, Integer fromStatus, String sceneText) {
        if (allowed(scene, action, fromStatus)) {
            return;
        }
        String text = StringUtils.hasText(sceneText) ? sceneText : "记录";
        throw new BusinessException("当前" + text + "状态（" + fromStatus + "）不允许执行该操作");
    }

    @Override
    public List<StateTransitionRule> listRules(String scene) {
        LambdaQueryWrapper<StateTransitionRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(scene), StateTransitionRule::getScene, scene)
                .orderByAsc(StateTransitionRule::getScene)
                .orderByAsc(StateTransitionRule::getAction)
                .orderByAsc(StateTransitionRule::getFromStatus);
        return list(wrapper);
    }

    @Override
    public void updateRule(Long id, Integer allowed, String description) {
        StateTransitionRule rule = getById(id);
        if (rule == null) {
            throw new BusinessException("状态规则不存在");
        }
        if (allowed != null) {
            if (allowed != 0 && allowed != 1) {
                throw new BusinessException("allowed 仅支持 0-禁止 / 1-允许");
            }
            rule.setAllowed(allowed);
        }
        if (description != null) {
            rule.setDescription(description);
        }
        updateById(rule);
        refresh();
        log.info("[状态机] 规则更新：{} / {} / from={} -> allowed={}",
                rule.getScene(), rule.getAction(), rule.getFromStatus(), rule.getAllowed());
    }

    private String key(String scene, String action, Integer fromStatus) {
        return scene + "|" + action + "|" + fromStatus;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        long last = lastLoadTime.get();
        if (now - last > CACHE_TTL_MS && lastLoadTime.compareAndSet(last, now)) {
            refresh();
        }
    }

    private synchronized void refresh() {
        Map<String, Boolean> fresh = new ConcurrentHashMap<>();
        for (StateTransitionRule rule : list()) {
            if (rule.getScene() == null || rule.getAction() == null || rule.getFromStatus() == null) {
                continue;
            }
            fresh.put(key(rule.getScene(), rule.getAction(), rule.getFromStatus()),
                    Integer.valueOf(1).equals(rule.getAllowed()));
        }
        cache.clear();
        cache.putAll(fresh);
        lastLoadTime.set(System.currentTimeMillis());
    }
}
