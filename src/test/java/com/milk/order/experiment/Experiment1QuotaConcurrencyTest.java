package com.milk.order.experiment;

import com.milk.order.module.product.dto.QuotaDeductItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验一：有限资源的并发扣减（超卖）。
 *
 * <p>场景：某日某品种机动配额 100 盒，200 个请求同时各买 1 盒。</p>
 *
 * <p>对照设计：</p>
 * <ol>
 *   <li><b>无保护版本</b>：沿用最朴素的「先查剩余 → 再写回 used+1」，用栅栏把所有线程的读取
 *       压到同一初始值，复现丢失更新（lost update）导致的超卖；</li>
 *   <li><b>有保护版本</b>：走 {@code DailyQuotaServiceImpl.deduct}，把
 *       {@code used_quota + n <= total_quota} 作为条件更新交给数据库行锁仲裁。</li>
 * </ol>
 *
 * <p>结论口径：成功数 = 系统对用户“承诺售出”的盒数；已售数 = 配额实际被消耗的盒数。
 * 两者一旦不相等（承诺 &gt; 消耗），即为超卖。</p>
 */
@DisplayName("实验一：机动配额并发扣减与超卖")
class Experiment1QuotaConcurrencyTest extends ExperimentSupport {

    /** 配额总量 */
    private static final int TOTAL = 100;
    /** 并发请求数 */
    private static final int THREADS = 200;
    /** 实验用的“假订单号”，deduct 只把它写进台账，不需要真实订单存在 */
    private static final long FAKE_ORDER_BASE = 700_000L;

    @Test
    @DisplayName("对照A（无保护）：先查后减丢失更新，承诺售出远超配额")
    void naiveReadThenWriteOversells() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, TOTAL);

        // 栅栏把 200 个线程全部卡在“已读到 used=0”之后、写回之前，
        // 从而确定性地复现“并发读到同一个旧值 → 相互覆盖”的丢失更新
        CyclicBarrier allReadBarrier = new CyclicBarrier(THREADS);

        ConcurrentOutcome outcome = runConcurrently(THREADS, i -> {
            Integer used = jdbcTemplate.queryForObject(
                    "SELECT used_quota FROM daily_quota WHERE quota_date = ? AND product_id = ?",
                    Integer.class, date, productId);
            int current = used == null ? 0 : used;
            if (current + 1 > TOTAL) {
                return false;   // 朴素实现的“库存校验”
            }
            allReadBarrier.await(30, TimeUnit.SECONDS);
            // 无条件写回“我读到的值 + 1”，没有版本/状态条件 → 并发写入互相覆盖
            jdbcTemplate.update("UPDATE daily_quota SET used_quota = ? WHERE quota_date = ? AND product_id = ?",
                    current + 1, date, productId);
            return true;
        });

        int actuallyUsed = usedQuota(date);
        report("实验一 · 对照A：无保护的“先查后减”",
                "配额总量", TOTAL,
                "并发请求数", THREADS,
                "系统承诺售出（成功率）", outcome.success,
                "配额实际消耗（used_quota）", actuallyUsed,
                "丢失更新次数", outcome.success - actuallyUsed,
                "超卖盒数（承诺 - 配额）", outcome.success - TOTAL);

        // 举证：承诺售出 > 实际消耗，说明大量并发写入被互相覆盖
        assertThat(outcome.success).isGreaterThan(actuallyUsed);
        // 举证：承诺售出超过配额总量，即超卖
        assertThat(outcome.success).isGreaterThan(TOTAL);
    }

    @Test
    @DisplayName("对照B（有保护）：条件更新由数据库行锁仲裁，不超卖")
    void guardedDeductNeverOversells() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        setQuota(date, TOTAL);

        ConcurrentOutcome outcome = runConcurrently(THREADS, i ->
                deductOne(FAKE_ORDER_BASE + i, date));

        int actuallyUsed = usedQuota(date);
        report("实验一 · 对照B：带条件更新的配额扣减",
                "配额总量", TOTAL,
                "并发请求数", THREADS,
                "扣减成功数", outcome.success,
                "扣减失败数", outcome.failed,
                "配额实际消耗（used_quota）", actuallyUsed,
                "超卖盒数", Math.max(0, outcome.success - TOTAL),
                "失败原因分布", outcome.errorSummary());

        // 成功数恰好等于配额总量：既不会超卖，也不会有资源被白白浪费
        assertThat(outcome.success).isEqualTo(TOTAL);
        assertThat(actuallyUsed).isEqualTo(TOTAL);
        assertThat(outcome.success).isLessThanOrEqualTo(TOTAL);
    }

    /** 走受保护的扣减路径（任一环节失败都会抛业务异常） */
    private boolean deductOne(long orderId, LocalDate date) {
        dailyQuotaService.deduct(orderId, date, List.of(new QuotaDeductItem(productId, 1)));
        return true;
    }
}
