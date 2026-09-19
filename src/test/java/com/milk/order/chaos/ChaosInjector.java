package com.milk.order.chaos;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 混沌注入器（**仅存在于测试代码中**）：在事务中间按概率抛异常，验证"中间失败"下的原子性与最终收敛。
 *
 * <p>为什么用测试域 AOP 而不是改业务代码：注入点必须是**真实事务内部**的一步
 * （扣配额、展开任务、签收都是支付/签收事务里的中间步骤），
 * 只有拦截真实 Bean 的调用才能得到"事务执行到一半失败"的效果；
 * 放在 `src/test/java` 下则保证生产代码里没有任何混沌相关逻辑。</p>
 *
 * <p>它是**可复现**的：注入用固定种子的随机数，同一实验重复执行得到同一注入序列。
 * 默认**撤防**，因此不会影响其它实验。</p>
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class ChaosInjector {

    /**
     * 默认注入点：事务中间的关键步骤。命名形式「类名（不含 Impl 后缀）+ 方法名」。
     *
     * <p><b>注意注入点必须位于某个外层事务内部</b>：本注入器是"先执行这一步、再抛异常"，
     * 只有外层事务尚在，才能验证"该步骤的写入随事务一起回滚"。
     * 若注入点自身就是事务边界（例如直接调用某个 Service 的入口方法），
     * 那么 proceed 已经提交，"抛异常"就变成了另一种现象——
     * <b>调用方看到失败、实际已生效</b>（模糊失败/悬挂），那是另一个课题，本实验不混用。</p>
     */
    private static final Set<String> DEFAULT_TARGETS = Set.of(
            "DailyQuotaService.deduct",
            "DeliveryTaskService.generateTasksForOrder",
            "DeliveryTaskService.signRecord",
            "DeliveryTaskService.autoSignOne",
            "OrderInfoService.completeOrderIfAllTasksDone");

    private static volatile boolean armed = false;
    private static volatile Set<String> targets = DEFAULT_TARGETS;
    private static volatile Random random = new Random(0L);
    private static volatile double probability = 0d;

    /** 剩余可注入次数（到 0 自动停止，便于"只注入前 N 次"的确定性场景） */
    private static final AtomicInteger REMAINING = new AtomicInteger(0);
    private static final AtomicInteger INJECTED = new AtomicInteger(0);
    private static final AtomicInteger MATCHED = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    /** 撤防（默认状态） */
    public static void disarm() {
        armed = false;
        REMAINING.set(0);
    }

    /** 复位统计与撤防（每个用例开始时调用） */
    public static void reset() {
        disarm();
        INJECTED.set(0);
        MATCHED.set(0);
        HITS.clear();
    }

    /** 全部注入点、固定种子、按概率注入最多 maxInjections 次 */
    public static void arm(long seed, double probability, int maxInjections) {
        arm(seed, probability, maxInjections, DEFAULT_TARGETS.toArray(new String[0]));
    }

    /**
     * 定向注入：只对包含给定关键字的注入点生效（例如只注入 `generateTasksForOrder`
     * 以精确构造"支付事务在任务展开处失败"）。
     */
    public static void arm(long seed, double probability, int maxInjections, String... targetKeywords) {
        random = new Random(seed);
        ChaosInjector.probability = probability;
        targets = Set.of(targetKeywords);
        INJECTED.set(0);
        REMAINING.set(Math.max(0, maxInjections));
        armed = true;
    }

    public static int injected() {
        return INJECTED.get();
    }

    public static int matched() {
        return MATCHED.get();
    }

    public static Map<String, Integer> hits() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        HITS.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }

    /**
     * 注入语义（重要）：**先执行这一步，再抛异常**。
     *
     * <p>这些注入点都运行在外层事务内部（支付/签收事务），因此"执行完再抛"精确对应
     * <b>「这一步的写入已经发生、但整个事务尚未提交」</b>——这正是要验证的中间失败语义：
     * 若在调用前就抛，事务里什么都还没做，反而验证不到"半截数据"。</p>
     */
    /**
     * 切点写在实现包上（`service..*.method`）而不是接口上：Spring Boot 默认开启
     * `proxy-target-class`（CGLIB），此时方法签名的声明类型是**实现类**，
     * 写接口名的切点匹配不到任何调用（这是一个实测踩到的坑：最初写接口名，注入次数恒为 0）。
     */
    @Around("execution(* com.milk.order.module.product.service..*.deduct(..))"
            + " || execution(* com.milk.order.module.delivery.service..*.generateTasksForOrder(..))"
            + " || execution(* com.milk.order.module.delivery.service..*.signRecord(..))"
            + " || execution(* com.milk.order.module.delivery.service..*.autoSignOne(..))"
            + " || execution(* com.milk.order.module.order.service..*.completeOrderIfAllTasksDone(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!armed) {
            return joinPoint.proceed();
        }
        String key = targetKey(joinPoint);
        if (key == null) {
            return joinPoint.proceed();
        }
        MATCHED.incrementAndGet();
        HITS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        boolean inject;
        synchronized (ChaosInjector.class) {
            inject = REMAINING.get() > 0 && random.nextDouble() < probability;
            if (inject) {
                REMAINING.decrementAndGet();
            }
        }
        Object result = joinPoint.proceed();
        if (inject) {
            int seq = INJECTED.incrementAndGet();
            log.warn("[混沌注入] 第 {} 次注入：{} 已执行，随后抛出异常（外层事务回滚）", seq, key);
            throw new ChaosInjectedException("混沌注入：事务中间故障 —— " + key);
        }
        return result;
    }

    /**
     * 命中的注入点标识（简单类名 + 方法名），不在目标集合内返回 null。
     *
     * <p>目标按「类名前缀 + 方法名」匹配，因此同一个目标既能匹配接口名也能匹配实现类名
     * （`DailyQuotaService` 可匹配到 `DailyQuotaServiceImpl`）。</p>
     */
    private String targetKey(ProceedingJoinPoint joinPoint) {
        String method = joinPoint.getSignature().getName();
        String simpleClass = joinPoint.getSignature().getDeclaringType().getSimpleName();
        for (String target : targets) {
            int dot = target.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String classPrefix = target.substring(0, dot);
            String methodName = target.substring(dot + 1);
            if (methodName.equals(method) && simpleClass.startsWith(classPrefix)) {
                return simpleClass + "." + method;
            }
        }
        return null;
    }
}
