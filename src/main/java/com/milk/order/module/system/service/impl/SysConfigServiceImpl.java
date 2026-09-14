package com.milk.order.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.system.entity.SysConfig;
import com.milk.order.module.system.mapper.SysConfigMapper;
import com.milk.order.module.system.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统参数配置实现：内存缓存 + 60 秒过期兜底；管理端修改后立即主动刷新
 */
@Slf4j
@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

    /** 缓存有效期（毫秒）：参数变更对业务生效的最大延迟 */
    private static final long CACHE_TTL_MS = 60_000L;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastLoadTime = new AtomicLong(0);

    @Override
    public String getString(String key, String defaultValue) {
        refreshIfStale();
        String value = cache.get(key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[系统参数] 配置 {}={} 不是合法整数，按默认值 {} 处理", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public List<SysConfig> listAll() {
        return list(new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getConfigKey));
    }

    @Override
    public void updateValue(Long id, String value) {
        SysConfig config = getById(id);
        if (config == null) {
            throw new BusinessException("配置项不存在");
        }
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("配置值不能为空");
        }
        config.setConfigValue(value.trim());
        updateById(config);
        // 立即刷新缓存，管理端改完即刻生效
        refresh();
        log.info("[系统参数] 配置 {} 已更新为 {}", config.getConfigKey(), config.getConfigValue());
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        long last = lastLoadTime.get();
        if (now - last > CACHE_TTL_MS && lastLoadTime.compareAndSet(last, now)) {
            refresh();
        }
    }

    private synchronized void refresh() {
        Map<String, String> fresh = new ConcurrentHashMap<>();
        for (SysConfig config : list()) {
            if (config.getConfigKey() != null && config.getConfigValue() != null) {
                fresh.put(config.getConfigKey(), config.getConfigValue());
            }
        }
        cache.clear();
        cache.putAll(fresh);
        lastLoadTime.set(System.currentTimeMillis());
    }
}
