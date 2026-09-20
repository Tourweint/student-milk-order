package com.milk.order.experiment;

import com.milk.order.module.delivery.vo.ShiftResultVO;
import com.milk.order.module.system.entity.SysConfig;
import com.milk.order.module.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十四：周末停送与调休例外（配送日历重排）。
 *
 * <p>命题：</p>
 * <ol>
 *   <li><b>周末并入工作日</b>：周六提前 2 天并入周四、周日提前 2 天并入周五（合并 quantity，非新建）。</li>
 *   <li><b>补课日照常</b>：例外 type=2 的周末不被重排，任务留在原配送日。</li>
 *   <li><b>停送日提前</b>：例外 type=1 的日期任务作废，并入前一个有效配送日。</li>
 *   <li><b>盒数守恒 + 幂等</b>：重排只改配送日与合并数量，在途盒数（status∈{1,2}）不变；
 *       CAS 作废后重复执行不再加量。</li>
 *   <li><b>单日上限前推</b>：首选目标日已满 3 盒时，继续向前找未满的工作日，而不是盲目加量。</li>
 *   <li><b>开关闸门</b>：`delivery.weekend.stop=false` 时拒绝执行（不改动现状行为）。</li>
 * </ol>
 */
@DisplayName("实验十四：周末停送与调休例外（日历重排）")
class Experiment14DeliveryCalendarRebalanceTest extends ExperimentSupport {

    private static final String CONFIG_WEEKEND_STOP = "delivery.weekend.stop";

    @Autowired
    private SysConfigService sysConfigService;

    @AfterEach
    void restoreWeekendStop() {
        setWeekendStop(false);
    }

    @Test
    @DisplayName("周末并入工作日 + 补课日跳过 + 停送日提前 + 盒数守恒 + 重复执行幂等")
    void calendarRebalanceMergesWeekendAndStopDays() {
        setWeekendStop(true);
        LocalDate today = LocalDate.now();
        // 取「至少 2 天后」的第一个周六，保证其提前 2 天的目标日仍在今天或之后、且落在订单配送区间内
        LocalDate sat = firstWeekendOnOrAfter(today.plusDays(2), DayOfWeek.SATURDAY);
        LocalDate end = sat.plusDays(8);
        long orderId = newPaidOrder(today, end, 1, 1L);

        // 下一个周六为补课日（当天照常配送）；本周二（sat+3）为调休放假（停送）
        insertException(sat.plusDays(7), 2, "周末补课");
        insertException(sat.plusDays(3), 1, "调休放假");

        int boxesBefore = boxesInFlight(orderId);
        ShiftResultVO first = deliveryTaskService.calendarRebalance(today.toString(), end.toString());
        int boxesAfter = boxesInFlight(orderId);
        int thuQtyAfterFirst = taskQuantity(orderId, sat.minusDays(2));
        ShiftResultVO second = deliveryTaskService.calendarRebalance(today.toString(), end.toString());

        report("实验十四 · 日历重排（周末 / 补课 / 停送 / 守恒 / 幂等）",
                "首次 requested/shifted/merged/created/skipped",
                first.getRequested() + " / " + first.getShifted() + " / " + first.getMerged()
                        + " / " + first.getCreated() + " / " + first.getSkipped(),
                "周六任务状态（期望 4 已取消）", taskStatus(orderId, sat),
                "周四数量（期望 2：自身 1 + 周六并入 1）", thuQtyAfterFirst,
                "周日任务状态（期望 4 已取消）", taskStatus(orderId, sat.plusDays(1)),
                "周五数量（期望 2：自身 1 + 周日并入 1）", taskQuantity(orderId, sat.minusDays(1)),
                "补课日（周六）状态/数量（期望 1 / 1 不重排）",
                taskStatus(orderId, sat.plusDays(7)) + " / " + taskQuantity(orderId, sat.plusDays(7)),
                "停送日（周二）状态（期望 4 已取消）", taskStatus(orderId, sat.plusDays(3)),
                "前一个工作日（周一）数量（期望 2）", taskQuantity(orderId, sat.plusDays(2)),
                "在途盒数（重排前 / 重排后，期望相等）", boxesBefore + " / " + boxesAfter,
                "重复执行 shifted（期望 0 幂等）", second.getShifted(),
                "重复执行后周四数量（期望仍 2）", taskQuantity(orderId, sat.minusDays(2)));

        assertThat(first.getShifted()).isPositive();
        // 周六 → 周四
        assertThat(taskStatus(orderId, sat)).isEqualTo(4);
        assertThat(thuQtyAfterFirst).isEqualTo(2);
        // 周日 → 周五
        assertThat(taskStatus(orderId, sat.plusDays(1))).isEqualTo(4);
        assertThat(taskQuantity(orderId, sat.minusDays(1))).isEqualTo(2);
        // 补课日不重排
        assertThat(taskStatus(orderId, sat.plusDays(7))).isEqualTo(1);
        assertThat(taskQuantity(orderId, sat.plusDays(7))).isEqualTo(1);
        // 停送日（周二）→ 前一个工作日（周一）
        assertThat(taskStatus(orderId, sat.plusDays(3))).isEqualTo(4);
        assertThat(taskQuantity(orderId, sat.plusDays(2))).isEqualTo(2);
        // 盒数守恒
        assertThat(boxesAfter).isEqualTo(boxesBefore);
        // 幂等
        assertThat(second.getShifted()).isZero();
        assertThat(taskQuantity(orderId, sat.minusDays(2))).isEqualTo(thuQtyAfterFirst);
    }

    @Test
    @DisplayName("首选目标日已满 3 盒时自动向前找未满的工作日")
    void calendarRebalanceOverflowGoesToEarlierWorkday() {
        setWeekendStop(true);
        LocalDate today = LocalDate.now();
        LocalDate sat = firstWeekendOnOrAfter(today.plusDays(4), DayOfWeek.SATURDAY);
        LocalDate start = sat.minusDays(3);
        LocalDate end = sat.plusDays(2);
        long orderId = newPaidOrder(start, end, 1, 1L);
        // 把「周六提前 2 天」的首选目标日（周四）顶满 3 盒 → 只能继续向前找周三
        jdbcTemplate.update("UPDATE delivery_task SET quantity = 3 WHERE order_id = ? AND delivery_date = ?",
                orderId, sat.minusDays(2));

        deliveryTaskService.calendarRebalance(start.toString(), end.toString());

        report("实验十四 · 单日上限前推",
                "周六任务状态（期望 4 已取消）", taskStatus(orderId, sat),
                "周四数量（期望 3 未被突破）", taskQuantity(orderId, sat.minusDays(2)),
                "周三数量（期望 2：自身 1 + 周六并入 1）", taskQuantity(orderId, sat.minusDays(3)));

        assertThat(taskStatus(orderId, sat)).isEqualTo(4);
        assertThat(taskQuantity(orderId, sat.minusDays(2))).isEqualTo(3);
        assertThat(taskQuantity(orderId, sat.minusDays(3))).isEqualTo(2);
    }

    @Test
    @DisplayName("周末停送开关关闭时拒绝执行日历重排")
    void calendarRebalanceRejectedWhenWeekendStopOff() {
        setWeekendStop(false);
        LocalDate today = LocalDate.now();
        String error = null;
        try {
            deliveryTaskService.calendarRebalance(today.toString(), today.plusDays(7).toString());
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        report("实验十四 · 开关闸门",
                "开关关闭时执行结果（期望 BusinessException）", error);

        assertThat(error).isNotNull().contains("BusinessException");
    }

    // ==================== 夹具辅助 ====================

    /** 开启/关闭「周末停送」开关（走 SysConfigService，缓存立即刷新） */
    private void setWeekendStop(boolean value) {
        SysConfig config = sysConfigService.lambdaQuery()
                .eq(SysConfig::getConfigKey, CONFIG_WEEKEND_STOP)
                .one();
        if (config == null) {
            throw new IllegalStateException("实验库缺少配置种子 " + CONFIG_WEEKEND_STOP + "，请按实验说明第 3 节同步");
        }
        sysConfigService.updateValue(config.getId(), String.valueOf(value));
    }

    /** 维护一条配送例外（1-停送，2-补送/补课） */
    private void insertException(LocalDate date, int type, String remark) {
        jdbcTemplate.update("INSERT INTO delivery_exception (exception_date, type, remark, deleted) "
                + "VALUES (?, ?, ?, 0)", date, type, remark);
    }

    /** 该订单「在途」盒数（待配送 + 配送中）：日历重排前后应守恒 */
    private int boxesInFlight(long orderId) {
        return count("SELECT COALESCE(SUM(quantity), 0) FROM delivery_task "
                + "WHERE order_id = ? AND status IN (1, 2)", orderId);
    }

    /** 从 from 起（含）第一个指定星期几的日期 */
    private LocalDate firstWeekendOnOrAfter(LocalDate from, DayOfWeek target) {
        LocalDate date = from;
        while (date.getDayOfWeek() != target) {
            date = date.plusDays(1);
        }
        return date;
    }
}
