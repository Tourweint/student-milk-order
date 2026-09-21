package com.milk.order.experiment;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.delivery.vo.ParentExemptionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 实验十八：家长端「当日豁免」。
 *
 * <p>命题：家长当天临时不要这份奶（病假/外出）时，系统应允许其**取消今天尚未送出的任务**，
 * 同时保证三件事不被绕过：</p>
 * <ul>
 *   <li>① 取消仍走**统一迁移出口**（规则闸门 + CAS + 迁移台账留痕 + 父过程自愈待办）；</li>
 *   <li>② 次数上限按「学生 × 自然月」严格不超限（**行锁 + 条件更新**，不是 `COUNT(*)` 判定）；</li>
 *   <li>③ 只豁免「今天 + 尚未送出（待配送）」的任务；零散订购的配额按台账回补原池。</li>
 * </ul>
 */
@DisplayName("实验十八：家长端当日豁免（限次 + 统一出口 + 配额回补）")
class Experiment18ParentExemptionTest extends ExperimentSupport {

    /** 任务状态：待配送 / 已取消 */
    private static final int TASK_PENDING = 1;
    private static final int TASK_CANCELLED = 4;

    /** 本用例创建的家长账号（sys_user 属种子数据，不在 CLEAN_TABLES 里，须自行清理） */
    private final List<String> createdParentUsernames = new java.util.ArrayList<>();

    @AfterEach
    void cleanParentUser() {
        SecurityContextHolder.clearContext();
        for (String username : createdParentUsernames) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
        }
        createdParentUsernames.clear();
    }

    @Test
    @DisplayName("正常豁免：今日待配送任务被取消（留痕）、配额回补原池、本月计数 1/3")
    void exemptionCancelsTodayPendingTaskAndRestoresQuota() {
        LocalDate today = LocalDate.now();
        setQuota(today, 10);
        long orderId = newPendingOrder(today, today, 2);
        orderInfoService.payOrder(orderId);            // 支付成功：扣 2 盒配额、展开当日任务
        long taskId = taskIdOf(orderId, today);
        int usedAfterPay = usedQuota(today);
        asParent(TAG + "-parent", studentId);

        ParentExemptionVO overview = deliveryTaskService.parentExemptionOverview();
        ParentExemptionVO result = deliveryTaskService.parentExemptToday("孩子病了");

        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskId);
        int signStatus = count("SELECT sign_status FROM delivery_record WHERE task_id = ?", taskId);
        int cancelLedger = count("SELECT COUNT(*) FROM process_transition_log WHERE entity_type = 'delivery_task' "
                + "AND entity_id = ? AND action = ? AND result = 1", taskId, StateTransitions.ACTION_TASK_CANCEL);
        int usedAfterExempt = usedQuota(today);

        report("实验十八 · 正常豁免",
                "豁免前可豁免任务数（期望 1）", overview.getExemptableCount(),
                "豁免前本月已用/上限（期望 0/3）", overview.getUsedCount() + "/" + overview.getMonthlyLimit(),
                "豁免后任务状态（期望 4 已取消）", taskStatus,
                "签收记录状态（期望 3 拒收，随取消联动）", signStatus,
                "CANCEL 迁移台账条数（期望 1）", cancelLedger,
                "配额：支付后已用 / 豁免后已用（期望 2 / 0）", usedAfterPay + " / " + usedAfterExempt,
                "计数：本月已用 / 剩余（期望 1 / 2）", result.getUsedCount() + " / " + result.getRemaining());

        assertThat(overview.getExemptableCount()).isEqualTo(1);
        assertThat(overview.getRemaining()).isEqualTo(3);
        assertThat(result.getUsedCount()).isEqualTo(1);
        assertThat(result.getRemaining()).isEqualTo(2);
        assertThat(taskStatus).isEqualTo(TASK_CANCELLED);
        assertThat(signStatus).isEqualTo(3);
        assertThat(cancelLedger).isEqualTo(1);
        assertThat(usedAfterPay).isEqualTo(2);
        assertThat(usedAfterExempt).isZero();
    }

    @Test
    @DisplayName("限次闸门：用完 3 次后第 4 次被拒（行锁 + 条件更新，不靠 COUNT 判定）")
    void monthlyLimitIsEnforcedByCounter() {
        LocalDate today = LocalDate.now();
        setQuota(today, 10);
        long firstOrderId = newPendingOrder(today, today, 1);
        orderInfoService.payOrder(firstOrderId);
        asParent(TAG + "-parent", studentId);

        // 直接把计数器推到上限（等价于"本月已用 3 次"），验证闸门而不是重复跑 3 天
        jdbcTemplate.update("INSERT INTO delivery_parent_exemption (student_id, exempt_month, used_count, deleted) "
                + "VALUES (?, ?, 3, 0)", studentId, today.toString().substring(0, 7));

        ParentExemptionVO overview = deliveryTaskService.parentExemptionOverview();
        Throwable overLimit = catchThrowable(() -> deliveryTaskService.parentExemptToday("超出次数"));
        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskIdOf(firstOrderId, today));
        int usedAfterPay = usedQuota(today);

        report("实验十八 · 限次闸门",
                "概览已用/上限/剩余（期望 3/3/0）",
                overview.getUsedCount() + "/" + overview.getMonthlyLimit() + "/" + overview.getRemaining(),
                "第 4 次豁免异常类型（期望 BusinessException）",
                overLimit == null ? "无" : overLimit.getClass().getSimpleName(),
                "异常提示", overLimit == null ? "—" : overLimit.getMessage(),
                "任务状态（期望 1 待配送，未被取消）", taskStatus,
                "配额已用（期望 1，未回补）", usedAfterPay);

        assertThat(overview.getRemaining()).isZero();
        assertThat(overLimit).isInstanceOf(BusinessException.class);
        assertThat(taskStatus).isEqualTo(TASK_PENDING);
        assertThat(usedAfterPay).isEqualTo(1);
    }

    @Test
    @DisplayName("前置边界：已送出的任务不可豁免；无可豁免任务时报错；他人学生不可豁免")
    void exemptionGuards() {
        LocalDate today = LocalDate.now();
        setQuota(today, 10);
        long orderId = newPendingOrder(today, today, 1);
        orderInfoService.payOrder(orderId);
        asParent(TAG + "-parent", studentId);

        // 送出后（配送中）不可豁免：奶已出库在途，取消等于把已出库的奶从记录里抹掉
        deliveryTaskService.batchStartDelivery(today.toString(), null);
        Throwable afterDispatch = catchThrowable(() -> deliveryTaskService.parentExemptToday("已送出后豁免"));
        int taskStatus = count("SELECT status FROM delivery_task WHERE id = ?", taskIdOf(orderId, today));

        // 换一个未绑定该学生的家长：应被数据范围拦住
        String otherParent = TAG + "-parent2";
        asParent(otherParent, otherStudentId());
        Throwable otherStudent = catchThrowable(() -> deliveryTaskService.parentExemptToday("越权豁免"));
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", otherParent);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", otherParent);
        SecurityContextHolder.clearContext();

        report("实验十八 · 前置边界",
                "已送出后豁免异常（期望 BusinessException）",
                afterDispatch == null ? "无" : afterDispatch.getMessage(),
                "任务状态（期望 2 配送中）", taskStatus,
                "他人学生豁免异常（期望 无今天可豁免任务）",
                otherStudent == null ? "无" : otherStudent.getMessage());

        assertThat(afterDispatch).isInstanceOf(BusinessException.class);
        assertThat(taskStatus).isEqualTo(2);
        assertThat(otherStudent).isInstanceOf(BusinessException.class);
        assertThat(otherStudent.getMessage()).contains("没有可豁免");
    }

    // ==================== 夹具 ====================

    /** 以家长身份（DataScope 解析依赖 sys_user.student_id + PARENT 角色）执行 */
    private void asParent(String username, long boundStudentId) {
        if (!createdParentUsernames.contains(username)) {
            jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, student_id, status, deleted) "
                            + "VALUES (?, 'x', ?, ?, 1, 0)",
                    username, TAG + "家长", boundStudentId);
            Long userId = id("SELECT id FROM sys_user WHERE username = ?", username);
            Long roleId = id("SELECT id FROM sys_role WHERE role_code = 'PARENT'");
            jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
            createdParentUsernames.add(username);
        }
        // 权限仅用于"是否已登录"的判定；DataScope 的角色来自 sys_user_role（与 JWT 登录态一致）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    /** 另一个学生（非当前家长绑定），用于越权用例 */
    private long otherStudentId() {
        String studentNo = TAG + "-S2";
        jdbcTemplate.update("INSERT INTO student (student_no, student_name, class_id, parent_id, deleted) "
                + "VALUES (?, ?, ?, ?, 0)", studentNo, TAG + "学生2", classId, PARENT_USER_ID + 1);
        return id("SELECT id FROM student WHERE student_no = ?", studentNo);
    }
}
