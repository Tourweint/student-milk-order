package com.milk.order.common.ratelimit;

import com.milk.order.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册接口限频（内存滑动窗口，按客户端 IP）
 * <p>
 * 规则：同一 IP 60 秒内最多 1 次注册；1 小时内最多 5 次。
 * 仅用于阻止脚本批量注册，不做登录、不做短信等场景的限频。
 */
@Slf4j
@Component
public class RegisterRateLimiter {

    /** 60 秒窗口：最多 1 次 */
    private static final long SECOND_WINDOW_MS = 60_000L;
    private static final int SECOND_WINDOW_LIMIT = 1;
    /** 1 小时窗口：最多 5 次 */
    private static final long HOUR_WINDOW_MS = 3_600_000L;
    private static final int HOUR_WINDOW_LIMIT = 5;

    /** ip -> 注册时间戳队列（升序） */
    private final Map<String, Deque<Long>> records = new ConcurrentHashMap<>();

    /**
     * 校验注册频率，超限抛业务异常（code=429）
     *
     * @param ip 客户端 IP；为空时放行（如内部调用）
     */
    public void check(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = records.computeIfAbsent(ip, k -> new LinkedList<>());
        synchronized (timestamps) {
            // 清理超过 1 小时的过期记录
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > HOUR_WINDOW_MS) {
                timestamps.pollFirst();
            }
            // 60 秒内已注册过
            if (!timestamps.isEmpty() && now - timestamps.peekLast() < SECOND_WINDOW_MS) {
                throw new BusinessException(429, "注册过于频繁，请稍后再试");
            }
            // 1 小时内达到上限
            if (timestamps.size() >= HOUR_WINDOW_LIMIT) {
                throw new BusinessException(429, "注册过于频繁，请稍后再试");
            }
            timestamps.addLast(now);
        }
    }

    /**
     * 每天凌晨 3 点清理一次，避免长时间运行内存累积
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        long now = System.currentTimeMillis();
        records.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                || now - entry.getValue().peekLast() > HOUR_WINDOW_MS);
        log.debug("注册限频记录清理完成，剩余 {} 个 IP", records.size());
    }
}
