package com.milk.order.experiment;

import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.product.service.DailyQuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验基础设施：统一准备夹具数据、并发压测工具与结果输出。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>实验只写独立实验库 {@code student_milk_order_test}（见 application-test.yml）；</li>
 *   <li>每个实验前重建夹具（年级/班级/学生/品类/奶品），实验后清空业务表，保证可重复运行；</li>
 *   <li>夹具一律用无唯一键冲突的 TAG 前缀命名，避免上一轮残留数据干扰。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class ExperimentSupport {

    /** 本轮实验的唯一标记：保证夹具命名、单号不与历史数据冲突 */
    protected static final String TAG = "EXP" + (System.currentTimeMillis() % 1_000_000L);

    /** 实验用下单人（家长用户ID），无外键约束，仅作归属标记 */
    protected static final long PARENT_USER_ID = 900_001L;

    /** 实验用单价（元） */
    protected static final BigDecimal UNIT_PRICE = new BigDecimal("3.00");

    private static final AtomicLong ORDER_SEQ = new AtomicLong(1);

    /** 实验结束后需要清空的业务表（保留 state_transition_rule / sys_config 种子数据） */
    private static final List<String> CLEAN_TABLES = List.of(
            "nutrition_intake", "delivery_record", "delivery_task",
            "daily_quota_usage", "daily_quota", "payment_record",
            "order_item", "order_info", "process_transition_log",
            "student", "class_info", "product", "product_category", "grade");

    @Autowired
    protected OrderInfoService orderInfoService;

    @Autowired
    protected DeliveryTaskService deliveryTaskService;

    @Autowired
    protected DailyQuotaService dailyQuotaService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected long gradeId;
    protected long classId;
    protected long studentId;
    protected long categoryId;
    protected long productId;

    @BeforeEach
    void setUpFixture() {
        jdbcTemplate.update("INSERT INTO grade (grade_name, grade_code, sort, deleted) VALUES (?, ?, 0, 0)",
                TAG + "年级", TAG);
        gradeId = id("SELECT id FROM grade WHERE grade_code = ?", TAG);

        jdbcTemplate.update("INSERT INTO class_info (class_name, grade_id, student_count, deleted) VALUES (?, ?, 0, 0)",
                TAG + "班", gradeId);
        classId = id("SELECT id FROM class_info WHERE class_name = ?", TAG + "班");

        jdbcTemplate.update("INSERT INTO student (student_no, student_name, class_id, parent_id, deleted) VALUES (?, ?, ?, ?, 0)",
                TAG + "-S1", TAG + "学生", classId, PARENT_USER_ID);
        studentId = id("SELECT id FROM student WHERE student_no = ?", TAG + "-S1");

        jdbcTemplate.update("INSERT INTO product_category (category_name, category_code, status, deleted) VALUES (?, ?, 1, 0)",
                TAG + "品类", TAG);
        categoryId = id("SELECT id FROM product_category WHERE category_code = ?", TAG);

        jdbcTemplate.update("INSERT INTO product (product_name, category_id, spec, price, status, sort, deleted) VALUES (?, ?, '250ml', ?, 1, 0, 0)",
                TAG + "奶", categoryId, UNIT_PRICE);
        productId = id("SELECT id FROM product WHERE product_name = ?", TAG + "奶");
    }

    @AfterEach
    void truncateExperimentData() {
        // 实验库为专用库，直接清空业务表即可获得确定性的初始状态
        for (String table : CLEAN_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    // ==================== 夹具构造 ====================

    /** 设置某日某品种的机动配额（新鲜池） */
    protected void setQuota(LocalDate date, int total) {
        jdbcTemplate.update("INSERT INTO daily_quota (quota_date, product_id, total_quota, used_quota, remark, deleted) VALUES (?, ?, ?, 0, ?, 0)",
                date, productId, total, TAG);
    }

    /** 读取某日某品种已消耗配额 */
    protected int usedQuota(LocalDate date) {
        Integer used = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(used_quota), 0) FROM daily_quota WHERE quota_date = ? AND product_id = ?",
                Integer.class, date, productId);
        return used == null ? 0 : used;
    }

    /**
     * 直接构造一个待支付订单（含明细）。
     *
     * <p>绕过 Controller/Service 下单入口，是为了让实验只关注“状态迁移与可靠性”这一段，
     * 不被下单校验、登录上下文等无关因素干扰。</p>
     *
     * @param start    配送开始日期
     * @param end      配送结束日期（单日散订时与 start 相同）
     * @param quantity 每日盒数
     */
    protected long newPendingOrder(LocalDate start, LocalDate end, int quantity) {
        String orderNo = TAG + "-O" + ORDER_SEQ.getAndIncrement();
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        BigDecimal amount = UNIT_PRICE.multiply(BigDecimal.valueOf(quantity)).multiply(BigDecimal.valueOf(days));
        jdbcTemplate.update("INSERT INTO order_info (order_no, student_id, user_id, class_id, order_type, status, "
                        + "total_amount, pay_amount, discount_amount, delivery_start_date, delivery_end_date, remark, deleted) "
                        + "VALUES (?, ?, ?, ?, 1, 1, ?, ?, 0, ?, ?, ?, 0)",
                orderNo, studentId, PARENT_USER_ID, classId, amount, amount, start, end, TAG);
        long orderId = id("SELECT id FROM order_info WHERE order_no = ?", orderNo);

        jdbcTemplate.update("INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal, deleted) "
                        + "VALUES (?, ?, ?, '250ml', ?, ?, ?, 0)",
                orderId, productId, TAG + "奶", UNIT_PRICE, quantity,
                UNIT_PRICE.multiply(BigDecimal.valueOf(quantity)));
        return orderId;
    }

    /** 读取订单状态码 */
    protected int orderStatus(long orderId) {
        Integer status = jdbcTemplate.queryForObject("SELECT status FROM order_info WHERE id = ?", Integer.class, orderId);
        return status == null ? -1 : status;
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected long id(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        if (value == null) {
            throw new IllegalStateException("夹具数据未生成：" + sql);
        }
        return value;
    }

    // ==================== 并发压测 ====================

    /** 并发执行体：返回 true 表示本次操作“对业务生效” */
    @FunctionalInterface
    protected interface ConcurrentAction {
        boolean run(int index) throws Exception;
    }

    /** 并发执行结果 */
    protected static class ConcurrentOutcome {
        public final int success;
        public final int failed;
        public final List<String> errors;

        ConcurrentOutcome(int success, int failed, List<String> errors) {
            this.success = success;
            this.failed = failed;
            this.errors = errors;
        }

        /** 失败原因的频次摘要（如 “BusinessException: 配额扣减冲突，请重试 ×73”） */
        public String errorSummary() {
            java.util.Map<String, Integer> freq = new java.util.TreeMap<>();
            for (String error : errors) {
                String key = error.length() > 300 ? error.substring(0, 300) : error;
                freq.merge(key, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            freq.forEach((k, v) -> sb.append("\n      ").append(v).append(" × ").append(k));
            return sb.isEmpty() ? "（无失败）" : sb.toString();
        }
    }

    /**
     * 用固定数量的线程同时发起操作，尽量制造真实并发。
     *
     * <p>所有线程先在同一道栅栏上就绪，再被同时放行，避免“先启动的线程已经跑完、
     * 后启动的线程才开始”导致并发度不足而掩盖竞态。</p>
     */
    protected ConcurrentOutcome runConcurrently(int threads, ConcurrentAction action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (action.run(index)) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        return new ConcurrentOutcome(success.get(), failed.get(), errors);
    }

    /** 实验数据存档目录（UTF-8），便于直接抄入实验文档与论文 */
    private static final java.nio.file.Path REPORT_DIR = java.nio.file.Paths.get("target", "experiment-reports");

    /** 打印并归档实验数据块 */
    protected void report(String title, Object... kvPairs) {
        StringBuilder sb = new StringBuilder("\n========== ").append(title).append(" ==========\n");
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            sb.append(String.format("  %-28s %s%n", kvPairs[i] + "：", kvPairs[i + 1]));
        }
        sb.append("==========================================");
        System.out.println(sb);
        try {
            java.nio.file.Files.createDirectories(REPORT_DIR);
            java.nio.file.Files.writeString(
                    REPORT_DIR.resolve(getClass().getSimpleName() + ".txt"),
                    sb + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("[实验存档] 写入失败：" + e.getMessage());
        }
    }
}
