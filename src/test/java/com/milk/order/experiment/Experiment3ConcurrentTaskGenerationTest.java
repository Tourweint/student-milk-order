package com.milk.order.experiment;

import com.milk.order.module.order.entity.OrderInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验三：并发重复展开配送任务的幂等性。
 *
 * <p>场景：一个覆盖 6 天的订单，只有 1 个品种，理论应生成 6 条配送任务。
 * 现让 16 个线程并发调用 {@code generateTasksForOrder}，模拟“支付回调重复投递 +
 * 手工补生成 + 定时展开”三者撞车。</p>
 *
 * <p>原始实现的缺陷：幂等靠“先 selectCount 再 insert”。并发下多个执行者会同时查不到，
 * 再同时插入，于是任务被重复生成（理论 6 条可能变成 12 条、18 条……）。</p>
 *
 * <p>改造后：业务唯一键 {@code uk_order_product_date(order_id, product_id, delivery_date)}
 * 由数据库仲裁，重复插入得到唯一键冲突并被幂等跳过，任务数量恒等于理论数量。</p>
 */
@DisplayName("实验三：并发重复生成配送任务")
class Experiment3ConcurrentTaskGenerationTest extends ExperimentSupport {

    private static final int DAYS = 6;
    private static final int THREADS = 16;
    private static final int EXPECTED_TASKS = DAYS;

    @Test
    @DisplayName("16 线程并发展开 6 天任务，任务数恒等于理论数")
    void concurrentGenerationProducesExactTaskCount() throws Exception {
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusDays(DAYS - 1);
        setQuota(start, 1000);

        // 夹具先造一个“周期订单”：直接构造单日订单后把结束日期延后（绕过散订单日校验）
        long orderId = newPendingOrder(start, start, 1);
        jdbcTemplate.update("UPDATE order_info SET delivery_end_date = ? WHERE id = ?", end, orderId);

        ConcurrentOutcome outcome = runConcurrently(THREADS, i -> {
            OrderInfo order = orderInfoService.getById(orderId);
            return deliveryTaskService.generateTasksForOrder(order) > 0;
        });

        int tasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", orderId);
        int records = count("SELECT COUNT(*) FROM delivery_record r "
                + "JOIN delivery_task t ON r.task_id = t.id WHERE t.order_id = ?", orderId);
        int distinctTaskNo = count("SELECT COUNT(DISTINCT task_no) FROM delivery_task WHERE order_id = ?", orderId);
        int distinctBusinessKey = count("SELECT COUNT(*) FROM ("
                + "SELECT order_id, product_id, delivery_date FROM delivery_task WHERE order_id = ? "
                + "GROUP BY order_id, product_id, delivery_date) t", orderId);

        report("实验三：并发重复生成配送任务（" + THREADS + " 线程 / " + DAYS + " 天）",
                "并发调用次数", THREADS,
                "本轮实际插入生效的线程数", outcome.success,
                "未插入任何行的线程数（幂等跳过）", outcome.failed,
                "理论任务数", EXPECTED_TASKS,
                "实际任务条数", tasks,
                "签收记录条数（应与任务一一对应）", records,
                "任务号去重后条数", distinctTaskNo,
                "业务键 (订单,品种,日期) 组合数", distinctBusinessKey,
                "失败原因分布", outcome.errorSummary());

        assertThat(tasks).isEqualTo(EXPECTED_TASKS);
        assertThat(records).isEqualTo(EXPECTED_TASKS);
        assertThat(distinctTaskNo).isEqualTo(EXPECTED_TASKS);
        assertThat(distinctBusinessKey).isEqualTo(EXPECTED_TASKS);
    }
}
