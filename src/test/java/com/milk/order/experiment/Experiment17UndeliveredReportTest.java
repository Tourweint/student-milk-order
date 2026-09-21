package com.milk.order.experiment;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.delivery.dto.UndeliveredReportRequest;
import com.milk.order.module.delivery.service.DeliveryUndeliveredReportService;
import com.milk.order.module.delivery.vo.UndeliveredReportVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 实验十七：奶站「当日未送达申报」。
 *
 * <p>命题：自动签收兜底的候选集判定用的是**信息态**（配送站点了"今日已送出"），
 * 而奶站申报表达的是**物理态**（奶实际没到校）。两者混同会让兜底把"没送到"签成"已签收"，
 * 凭空生成签收记录与营养摄入（虚假业务事实）。本实验验证三件事：</p>
 * <ul>
 *   <li>① 申报后该任务**排除出候选集**，且**单条入口也跳过**（记录不签收、任务仍配送中、无营养摄入）；</li>
 *   <li>② 幂等与前置守卫：重复申报只留一条；非「配送中」的任务不允许申报；</li>
 *   <li>③ 跟进只做登记（写说明与人/时间），**不改任务状态**；跟进状态可从待办列表回读与筛选。</li>
 * </ul>
 */
@DisplayName("实验十七：奶站当日未送达申报（自动签收候选集排除）")
class Experiment17UndeliveredReportTest extends ExperimentSupport {

    /** 任务状态：配送中 */
    private static final int TASK_DISPATCHING = 2;
    /** 签收状态：未签收 */
    private static final int SIGN_PENDING = 2;

    @Autowired
    private DeliveryUndeliveredReportService undeliveredReportService;

    @Test
    @DisplayName("申报后任务被排除出自动签收候选集：单条入口也跳过，零虚假签收零营养摄入")
    void reportedTaskIsExcludedFromAutoSign() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        long orderId = paidAndDispatchedOrder(yesterday);
        long taskId = taskIdOf(orderId, yesterday);
        long recordId = recordIdOf(taskId);

        List<Long> beforeReport = deliveryTaskService.listExpiredAutoSignRecordIds(200);
        assertThat(beforeReport).contains(recordId);

        report(taskId, "车辆故障，当日未送达");

        List<Long> afterReport = deliveryTaskService.listExpiredAutoSignRecordIds(200);
        deliveryTaskService.autoSignOne(recordId);

        int signStatus = count("SELECT sign_status FROM delivery_record WHERE id = ?", recordId);
        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int intakeRows = count("SELECT COUNT(*) FROM nutrition_intake WHERE delivery_record_id = ?", recordId);
        int ledgerRows = count("SELECT COUNT(*) FROM process_transition_log WHERE entity_type = 'delivery_task' "
                + "AND entity_id = ? AND action = 'SIGN' AND result = 1", taskId);

        report("实验十七 · 申报后排除出自动签收",
                "申报前任务是否在候选集（期望 在）", beforeReport.contains(recordId),
                "申报后任务是否在候选集（期望 不在）", afterReport.contains(recordId),
                "单条自动签收后签收状态（期望 2 未签收）", signStatus,
                "任务状态（期望 2 配送中）", taskStatus,
                "营养摄入条数（期望 0）", intakeRows,
                "SIGN 迁移台账条数（期望 0）", ledgerRows);

        assertThat(afterReport).doesNotContain(recordId);
        assertThat(signStatus).isEqualTo(SIGN_PENDING);
        assertThat(taskStatus).isEqualTo(TASK_DISPATCHING);
        assertThat(intakeRows).isZero();
        assertThat(ledgerRows).isZero();
    }

    @Test
    @DisplayName("幂等与前置守卫：重复申报只留一条；任务已离开「配送中」不允许申报")
    void reportIsIdempotentAndStatusGuarded() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        long orderId = paidAndDispatchedOrder(yesterday);
        long taskId = taskIdOf(orderId, yesterday);
        report(taskId, "道路中断");

        Throwable duplicate = catchThrowable(() -> report(taskId, "重复申报"));
        int reportRows = count("SELECT COUNT(*) FROM delivery_undelivered_report WHERE task_id = ?", taskId);

        // 签收后任务离开「配送中」：申报只对"兜底可能误签"的记录有意义，此时应被拒绝
        signRecord(recordIdOf(taskId));
        Throwable afterSigned = catchThrowable(() -> report(taskId, "已完成后再申报"));

        report("实验十七 · 幂等与前置守卫",
                "重复申报异常类型（期望 BusinessException）", duplicate == null ? "无" : duplicate.getClass().getSimpleName(),
                "重复申报提示", duplicate == null ? "—" : duplicate.getMessage(),
                "申报表行数（期望 1）", reportRows,
                "已签收后申报异常类型（期望 BusinessException）",
                afterSigned == null ? "无" : afterSigned.getClass().getSimpleName(),
                "已签收后申报提示", afterSigned == null ? "—" : afterSigned.getMessage());

        assertThat(duplicate).isInstanceOf(BusinessException.class);
        assertThat(reportRows).isEqualTo(1);
        assertThat(afterSigned).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("跟进只做登记：任务状态不变；待办列表按跟进状态筛选并回显任务/学生/班级")
    void handleOnlyBookkeepsAndListEchoesTask() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        long orderId = paidAndDispatchedOrder(yesterday);
        long taskId = taskIdOf(orderId, yesterday);
        report(taskId, "奶未备齐");
        long reportId = id("SELECT id FROM delivery_undelivered_report WHERE task_id = ?", taskId);

        // 列表查询强制登录（与 pageTasks / pendingSign 同一口径：dataScopeResolver.resolve()），
        // 故本用例以管理员身份执行，顺带验证 handleBy 落在跟进人上
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        IPage<UndeliveredReportVO> pending;
        IPage<UndeliveredReportVO> afterHandle;
        try {
            pending = undeliveredReportService.pageReports(1L, 10L, yesterday.toString(), 0, null);
            undeliveredReportService.handle(reportId, "已线下补送，家长已确认");
            // 重复点击跟进：幂等，不覆盖首次跟进人/时间
            undeliveredReportService.handle(reportId, "重复点击");
            afterHandle = undeliveredReportService.pageReports(1L, 10L, yesterday.toString(), 1, null);
        } finally {
            SecurityContextHolder.clearContext();
        }
        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        UndeliveredReportVO row = afterHandle.getRecords().get(0);

        report("实验十七 · 跟进只做登记",
                "跟进前待跟进总数（期望 1）", pending.getTotal(),
                "跟进后已跟进总数（期望 1）", afterHandle.getTotal(),
                "跟进说明（期望 首次说明，不被重复点击覆盖）", row.getHandleRemark(),
                "跟进人（期望 admin）", row.getHandleBy(),
                "任务状态（期望 2 配送中，本操作不改状态）", taskStatus,
                "回显：任务号 / 学生 / 班级 / 盒数", row.getTaskNo() + " / " + row.getStudentName()
                        + " / " + row.getClassName() + " / " + row.getQuantity());

        assertThat(pending.getTotal()).isEqualTo(1);
        assertThat(afterHandle.getTotal()).isEqualTo(1);
        assertThat(row.getHandleRemark()).isEqualTo("已线下补送，家长已确认");
        assertThat(row.getHandleBy()).isEqualTo("admin");
        assertThat(row.getStudentName()).isEqualTo(TAG + "学生");
        assertThat(row.getClassName()).isEqualTo(TAG + "班");
        assertThat(row.getQuantity()).isEqualTo(1);
        assertThat(row.getTaskStatus()).isEqualTo(TASK_DISPATCHING);
        assertThat(taskStatus).isEqualTo(TASK_DISPATCHING);
    }

    /** 夹具：零散订单 → 支付 → 当日已送出（任务进入「配送中」） */
    private long paidAndDispatchedOrder(LocalDate date) {
        setQuota(date, 100);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);
        deliveryTaskService.batchStartDelivery(date.toString(), null);
        return orderId;
    }

    private void report(long taskId, String reason) {
        UndeliveredReportRequest request = new UndeliveredReportRequest();
        request.setTaskId(taskId);
        request.setReason(reason);
        undeliveredReportService.report(request);
    }
}
