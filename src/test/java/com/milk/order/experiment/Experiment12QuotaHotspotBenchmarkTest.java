package com.milk.order.experiment;

import com.milk.order.module.product.dto.QuotaDeductItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十二：性能基准——配额池热点行争用（TPS / P99）。
 *
 * <p><b>要验证的假设</b>：扣减是「加行锁读剩余 → 写回已售」的读-改-写，因此
 * <b>同一个池子上的扣减会被行锁串行化</b>——并发度再高，吞吐上限也只是
 * {@code 1 / 单次事务（临界区）耗时}；把这个池子分散到多行，吞吐才会随行数上升。</p>
 *
 * <p>这正是本项目"一致性优先"的直接代价，也是讨论"单池 vs 分段池"的依据，
 * 所以它属于可靠性论证的一部分，而不只是"跑个压测"。</p>
 *
 * <p><b>测量口径与边界（必须写清）</b>：</p>
 * <ul>
 *   <li>压的是<b>进程内服务调用</b>（{@code DailyQuotaService.deduct}），不含 HTTP 与 JSON 序列化，
 *       因为要单独暴露"行锁串行化"这一个因素；</li>
 *   <li>每个配置前先做一轮<b>预热</b>（不统计），避免把 JIT 预热成本算进指标；</li>
 *   <li>每个配置用**独立的新品种池**，因此各配置之间互不干扰，且每个配置结束后都能断言"账实一致"；</li>
 *   <li>本项目的配额池唯一键是 {@code (quota_date, product_id)}，<b>没有"按班级分段"这一维</b>，
 *       所以"分段池"用**多品种池**做等价代理（同为"把热点分散到多行"）；
 *       真按班级分段需要改配额维度与分配算法，属后续工作；</li>
 *   <li>池容量给足（不触发"卖完即止"），目的是测吞吐而不是测失败路径（超卖拒绝已在实验一验证）。</li>
 * </ul>
 */
@DisplayName("实验十二：性能基准——配额池热点行争用")
class Experiment12QuotaHotspotBenchmarkTest extends ExperimentSupport {

    /** 每个配置的测量时长（毫秒）：够长以摊平抖动，够短以控制总时长 */
    private static final long DURATION_MS = 1500L;
    /** 预热时长（毫秒）：先把 JIT 与连接池喂热，再开始计数 */
    private static final long WARMUP_MS = 500L;
    /** 每个池子的总容量：足够大，避免扣光导致失败干扰吞吐测量 */
    private static final int POOL_TOTAL = 1_000_000;
    /** 合成订单号的高位起点：与夹具订单（自增 id）错开，台账唯一键只要求"每次扣减一个新订单" */
    private static final AtomicLong SYNTHETIC_ORDER_SEQ = new AtomicLong(90_000_000L);

    @Test
    @DisplayName("热点单池随并发度饱和，分散到多行后吞吐恢复")
    void hotspotRowLockSerializesDeduction() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);

        // 预热：4 线程 1 池，结果不统计
        runConfig("预热", 4, 1, date, WARMUP_MS);

        // A 组：热点单池，扫描并发度（全部线程抢同一个池子）
        ConfigResult hot1 = runConfig("热点单池 · 1 线程", 1, 1, date, DURATION_MS);
        ConfigResult hot8 = runConfig("热点单池 · 8 线程", 8, 1, date, DURATION_MS);
        ConfigResult hot32 = runConfig("热点单池 · 32 线程", 32, 1, date, DURATION_MS);
        // B 组：同一并发度，把配额分散到多个品种池（同日期其余维度不变）
        ConfigResult spread8 = runConfig("分散 8 池 · 32 线程", 32, 8, date, DURATION_MS);
        ConfigResult spread32 = runConfig("分散 32 池 · 32 线程", 32, 32, date, DURATION_MS);

        // 口径说明：单线程 TPS 是"单次端到端耗时的倒数"，**不是**临界区吞吐上限——
        // 多线程可以把"提交/连接获取/幂等查询"等锁外开销与锁内临界区流水重叠，
        // 因此热点并发吞吐会高于单线程 TPS（实测约 3.5 倍），但仍远低于线性扩展。
        double singleThreadTps = hot1.opsPerSecond();

        report("实验十二 · 配额池热点行争用（TPS / 延迟）",
                "场景", "[线程数 / 池数] 耗时 → 成功数 / TPS / p50 / p95 / p99 / max（成功口径）",
                hot1.label, hot1.summary(),
                hot8.label, hot8.summary(),
                hot32.label, hot32.summary(),
                spread8.label, spread8.summary(),
                spread32.label, spread32.summary(),
                "单线程 TPS（= 1/单次端到端耗时，含锁外开销）", round(singleThreadTps) + " ops/s",
                "热点 32 线程 / 单线程", round(hot32.opsPerSecond() / singleThreadTps) + " ×（并发度 32× ⇒ 远非线性）",
                "热点 8 线程 → 32 线程的吞吐增幅",
                round(hot32.opsPerSecond() / hot8.opsPerSecond()) + " ×（并发度 4× ⇒ 已饱和）",
                "热点 32 线程 p50 / 单线程 p50", round(hot32.p50Ms() / hot1.p50Ms()) + " ×（并发度 32× ⇒ 排队而非变慢）",
                "分散 32 池 / 热点 32 线程", round(spread32.opsPerSecond() / hot32.opsPerSecond()) + " ×",
                "分散 8 池 / 热点 32 线程", round(spread8.opsPerSecond() / hot32.opsPerSecond()) + " ×",
                "分散 8 池 → 32 池的吞吐增幅",
                round(spread32.opsPerSecond() / spread8.opsPerSecond()) + " ×（收益递减：已接近锁外瓶颈）",
                "所有配置失败数合计（应 0）",
                hot1.failed + hot8.failed + hot32.failed + spread8.failed + spread32.failed,
                "账实一致校验", "每个配置：池已售合计 == 成功扣减数 == 台账行数（见下方断言）");

        // ① 正确性优先：压力下账实仍然一致，且没有超卖（性能不能让一致性妥协）
        for (ConfigResult result : List.of(hot1, hot8, hot32, spread8, spread32)) {
            assertThat(result.usedQuota()).as("%s：池已售合计应等于成功扣减数", result.label)
                    .isEqualTo(result.success);
            assertThat(result.ledgerRows()).as("%s：台账行数应等于成功扣减数", result.label)
                    .isEqualTo(result.success);
            assertThat(result.oversold()).as("%s：不应出现超卖", result.label).isZero();
            assertThat(result.failed).as("%s：容量充足时不应有失败（失败=%s）", result.label, result.errorSample)
                    .isZero();
        }

        // ② 热点池被行锁串行化：并发度成倍提高后吞吐饱和，延迟按并发度排队上升
        assertThat(hot32.opsPerSecond())
                .as("并发度从 8 提到 32（4×），热点吞吐不应翻倍：说明瓶颈是行锁而非 CPU/线程数")
                .isLessThan(hot8.opsPerSecond() * 2);
        assertThat(hot32.opsPerSecond())
                .as("32 倍并发换来的吞吐提升应远小于 32 倍（实测约 3.5 倍）")
                .isLessThan(hot1.opsPerSecond() * 5);
        assertThat(hot32.p50Ms())
                .as("热点单池下 32 线程的单次耗时（含锁等待）应明显高于单线程")
                .isGreaterThan(hot1.p50Ms() * 3);
        assertThat(hot32.p99Ms())
                .as("热点单池下 32 线程的 p99 应明显高于单线程（排队效应）")
                .isGreaterThan(hot1.p99Ms() * 3);

        // ③ 分散到多行后吞吐显著回升（同一份代码、同一份数据规模，只是热点不在一行上）。
        //    注意：不做"分散后 p99 更低"的断言——分散配置吞吐高、样本多，尾部更容易撞上 GC 停顿，
        //    p99 的跨配置比较不可靠；可靠的是吞吐对比，以及热点内部的排队上升（断言 ②）。
        assertThat(spread32.opsPerSecond())
                .as("分散到 32 个池后吞吐应显著高于单池 32 线程")
                .isGreaterThan(hot32.opsPerSecond() * 2);
        assertThat(spread8.opsPerSecond())
                .as("仅分散到 8 个池也应显著高于单池 32 线程")
                .isGreaterThan(hot32.opsPerSecond() * 2);
    }

    // ==================== 单配置压测 ====================

    private ConfigResult runConfig(String label, int threads, int poolCount, LocalDate date, long durationMs)
            throws Exception {
        // 每个配置用全新的品种池，保证配置间相互隔离、且测后能精确校验账实一致
        List<Long> productIds = new ArrayList<>();
        for (int i = 0; i < poolCount; i++) {
            String name = TAG + "-" + label.hashCode() + "-" + i + "-" + System.nanoTime();
            jdbcTemplate.update("INSERT INTO product (product_name, category_id, spec, price, status, sort, deleted) "
                    + "VALUES (?, ?, '250ml', ?, 1, 0, 0)", name, categoryId, UNIT_PRICE);
            long productId = id("SELECT id FROM product WHERE product_name = ?", name);
            jdbcTemplate.update("INSERT INTO daily_quota (quota_date, product_id, total_quota, used_quota, remark, deleted) "
                    + "VALUES (?, ?, ?, 0, ?, 0)", date, productId, POOL_TOTAL, TAG);
            productIds.add(productId);
        }

        AtomicLong orderSeq = new AtomicLong(SYNTHETIC_ORDER_SEQ.getAndAdd(1_000_000L));
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<List<Long>> latencyByThread = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            latencyByThread.add(new ArrayList<>());
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            // 线程固定绑定一个池：池少于线程时多个线程共享同一个池（这就是争用的来源）
            final long productId = productIds.get(i % poolCount);
            final List<Long> latencies = latencyByThread.get(i);
            pool.add(daemonThread("bench-" + label + "-" + i, startGate, () -> {
                long deadline = System.nanoTime() + durationMs * 1_000_000L;
                QuotaDeductItem item = new QuotaDeductItem();
                item.setProductId(productId);
                item.setBoxes(1);
                List<QuotaDeductItem> items = List.of(item);
                while (System.nanoTime() < deadline) {
                    long orderId = orderSeq.incrementAndGet();
                    long begin = System.nanoTime();
                    try {
                        dailyQuotaService.deduct(orderId, date, items);
                        success.incrementAndGet();
                        latencies.add(System.nanoTime() - begin);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        if (errors.size() < 5) {
                            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }));
        }

        long wallStart = System.nanoTime();
        pool.forEach(Thread::start);
        startGate.countDown();
        for (Thread thread : pool) {
            thread.join(durationMs + 30_000L);
        }
        double seconds = (System.nanoTime() - wallStart) / 1_000_000_000.0;

        List<Long> latencies = new ArrayList<>();
        latencyByThread.forEach(latencies::addAll);

        // 账实一致与超卖校验所需的两项读数
        StringBuilder idList = new StringBuilder();
        for (Long productId : productIds) {
            if (idList.length() > 0) {
                idList.append(',');
            }
            idList.append(productId);
        }
        int usedQuota = count("SELECT COALESCE(SUM(used_quota), 0) FROM daily_quota WHERE product_id IN (" + idList + ")");
        int ledgerRows = count("SELECT COUNT(*) FROM daily_quota_usage WHERE product_id IN (" + idList + ")");
        int oversold = count("SELECT COUNT(*) FROM daily_quota WHERE product_id IN (" + idList
                + ") AND used_quota > total_quota");

        return new ConfigResult(label, threads, poolCount, success.get(), failed.get(),
                errors.isEmpty() ? null : errors.get(0), seconds, latencies, usedQuota, ledgerRows, oversold);
    }

    /** 单个配置的测量结果 */
    private record ConfigResult(String label, int threads, int poolCount, int success, int failed,
                                String errorSample, double seconds, List<Long> latenciesNanos,
                                int usedQuota, int ledgerRows, int oversold) {

        double opsPerSecond() {
            return seconds <= 0 ? 0 : success / seconds;
        }

        double p50Ms() {
            return percentileMs(50);
        }

        double p95Ms() {
            return percentileMs(95);
        }

        double p99Ms() {
            return percentileMs(99);
        }

        double maxMs() {
            return latenciesNanos.isEmpty() ? 0 : round(latenciesNanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6);
        }

        double percentileMs(int percentile) {
            if (latenciesNanos.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(latenciesNanos);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            long nanos = sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
            return round(nanos / 1e6);
        }

        String summary() {
            return "[" + threads + " / " + poolCount + "] " + round(seconds) + "s → "
                    + success + " 次 / " + round(opsPerSecond()) + " TPS / "
                    + p50Ms() + " / " + p95Ms() + " / " + p99Ms() + " / " + maxMs() + " ms";
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
