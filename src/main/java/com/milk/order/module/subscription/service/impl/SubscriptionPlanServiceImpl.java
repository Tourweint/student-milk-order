package com.milk.order.module.subscription.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.mapper.MealPackageMapper;
import com.milk.order.module.subscription.dto.CreateSubscriptionRequest;
import com.milk.order.module.subscription.entity.SubscriptionPlan;
import com.milk.order.module.subscription.mapper.SubscriptionPlanMapper;
import com.milk.order.module.subscription.service.SubscriptionPlanService;
import com.milk.order.module.subscription.vo.SubscriptionPlanVO;
import com.milk.order.module.system.service.StateMachineService;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPlanServiceImpl extends ServiceImpl<SubscriptionPlanMapper, SubscriptionPlan> implements SubscriptionPlanService {

    private final StudentMapper studentMapper;
    private final MealPackageMapper mealPackageMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final OrderInfoService orderInfoService;
    private final DeliveryTaskService deliveryTaskService;
    private final DataScopeResolver dataScopeResolver;
    private final StateMachineService stateMachineService;

    /**
     * 自代理：processDuePlans 循环内若直接 this.triggerRenewal(...)，同类内部调用不经过代理，
     * triggerRenewal 上的 @Transactional 失效，会造成「续订订单已生成但计划时间链未推进」，
     * 下一轮定时任务将对同一计划重复续订（重复扣款）。
     * 注入自身接口代理，保证每笔续订（订单生成 + 计划时间链推进）在同一事务内。
     */
    @Lazy
    @Autowired
    private SubscriptionPlanService self;

    /** 计划状态：0-已关闭，1-已开启，2-已暂停 */
    private static final int STATUS_CLOSED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_PAUSED = 2;

    // ==================== 查询 ====================

    @Override
    public IPage<SubscriptionPlanVO> pagePlans(Long pageNum, Long pageSize, Integer status) {
        // 数据权限：家长仅能查看自己学生的续订计划
        DataScope scope = dataScopeResolver.resolve();
        LambdaQueryWrapper<SubscriptionPlan> wrapper = new LambdaQueryWrapper<>();
        if (scope.getStudentId() != null) {
            wrapper.eq(SubscriptionPlan::getStudentId, scope.getStudentId());
        } else if (scope.isScoped() && scope.getClassId() == null) {
            // 家长角色但未绑定学生
            throw new BusinessException("请先绑定学生信息");
        }

        Page<SubscriptionPlan> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        wrapper.eq(status != null, SubscriptionPlan::getStatus, status)
                .orderByDesc(SubscriptionPlan::getId);
        IPage<SubscriptionPlan> planPage = page(page, wrapper);
        List<SubscriptionPlanVO> voList = convert(planPage.getRecords());

        Page<SubscriptionPlanVO> result = new Page<>(planPage.getCurrent(), planPage.getSize(), planPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public SubscriptionPlanVO getPlanDetail(Long id) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        return convert(Collections.singletonList(plan)).get(0);
    }

    // ==================== 创建/修改/暂停/恢复/关闭 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPlan(CreateSubscriptionRequest request) {
        // 数据权限：家长仅能为自己绑定的学生开启续订
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() != null) {
            if (!scope.getStudentId().equals(request.getStudentId())) {
                throw new BusinessException(403, "只能为自己的孩子开启续订");
            }
        } else if (scope.isScoped() && scope.getClassId() == null) {
            throw new BusinessException("请先绑定学生信息");
        }

        // 校验学生
        Student student = studentMapper.selectById(request.getStudentId());
        if (student == null) {
            throw new BusinessException("学生不存在");
        }
        // 校验套餐
        MealPackage pkg = mealPackageMapper.selectById(request.getPackageId());
        if (pkg == null) {
            throw new BusinessException("套餐不存在");
        }
        // 续订规则：仅月度套餐支持自动续订；学期套餐为一次性购买，散订订单为一次性配送
        if (pkg.getPackageType() != null && pkg.getPackageType() == 2) {
            throw new BusinessException("学期套餐为一次性购买，不支持自动续订");
        }
        // 校验原订单
        OrderInfo original = orderInfoMapper.selectById(request.getOriginalOrderId());
        if (original == null) {
            throw new BusinessException("原订单不存在");
        }
        Integer oStatus = original.getStatus();
        if (!OrderStatus.PAID.getCode().equals(oStatus)
                && !OrderStatus.DELIVERING.getCode().equals(oStatus)
                && !OrderStatus.COMPLETED.getCode().equals(oStatus)) {
            throw new BusinessException("仅已支付/配送中/已完成订单可开启续订");
        }
        // 散订订单（起止同日，一次性配送）不可续订：续订会按周期展开成每日配送，与散订语义不符
        if (original.getDeliveryStartDate() != null
                && original.getDeliveryStartDate().equals(original.getDeliveryEndDate())) {
            throw new BusinessException("散订订单为一次性配送，不支持自动续订，如需再次购买请直接下单");
        }
        // 同一学生同一原订单不能重复开启
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getOriginalOrderId, request.getOriginalOrderId())
                .in(SubscriptionPlan::getStatus, STATUS_ACTIVE, STATUS_PAUSED));
        if (exists != null && exists > 0) {
            throw new BusinessException("该订单已开启自动续订");
        }
        // 重复订阅校验：同一学生同一时点只允许一个生效计划（开启/暂停均算生效，防止并行扣款）
        Long activeCount = baseMapper.selectCount(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getStudentId, request.getStudentId())
                .in(SubscriptionPlan::getStatus, STATUS_ACTIVE, STATUS_PAUSED));
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException("该学生已有生效中的续订计划，请先关闭或暂停现有计划后再开启新计划");
        }

        // 下次续订时间 = 原订单配送结束日 + 1天 的凌晨2点
        LocalDate nextDate = original.getDeliveryEndDate().plusDays(1);
        LocalDateTime nextRenewal = LocalDateTime.of(nextDate, LocalTime.of(2, 0));

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setStudentId(request.getStudentId());
        plan.setUserId(original.getUserId());
        plan.setPackageId(request.getPackageId());
        plan.setOriginalOrderId(request.getOriginalOrderId());
        plan.setCycleType(request.getCycleType() == null ? 1 : request.getCycleType());
        plan.setStatus(STATUS_ACTIVE);
        plan.setNextRenewalTime(nextRenewal);
        plan.setReminderSent(0);
        plan.setRemark(request.getRemark());
        save(plan);
        return plan.getId();
    }

    @Override
    public void updatePlan(SubscriptionPlan plan) {
        if (plan.getId() == null) {
            throw new BusinessException("计划ID不能为空");
        }
        SubscriptionPlan existing = getPlan(plan.getId());
        checkPlanAccess(existing);
        // 只允许修改备注和周期
        existing.setRemark(plan.getRemark());
        if (plan.getCycleType() != null) {
            existing.setCycleType(plan.getCycleType());
        }
        updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pausePlan(Long id, String reason, boolean keepPendingTasks) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        // 状态机规则：已开启→已暂停（管理端可配置是否允许）
        stateMachineService.assertAllowed(StateTransitions.SCENE_SUBSCRIPTION_PLAN,
                StateTransitions.ACTION_PAUSE, plan.getStatus(), "续订计划");
        // CAS 条件更新：并发暂停/关闭/触发续订时仅一方成功
        boolean paused = lambdaUpdate()
                .eq(SubscriptionPlan::getId, id)
                .eq(SubscriptionPlan::getStatus, STATUS_ACTIVE)
                .set(SubscriptionPlan::getStatus, STATUS_PAUSED)
                .set(SubscriptionPlan::getPauseTime, LocalDateTime.now())
                .set(SubscriptionPlan::getPauseReason,
                        reason == null || reason.isEmpty() ? "家长/管理端暂停" : reason)
                .update();
        if (!paused) {
            throw new BusinessException("计划状态已变更，请刷新后重试");
        }
        // 已生成配送任务（已支付权益）默认保留照常配送；可选取消未配送任务
        if (!keepPendingTasks) {
            int cancelled = deliveryTaskService.cancelPendingTasksForOrder(plan.getOriginalOrderId());
            log.info("【自动续订】计划{} 暂停并取消未配送任务 {} 条", id, cancelled);
        } else {
            log.info("【自动续订】计划{} 已暂停，未配送任务保留照常配送", id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumePlan(Long id) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        // 状态机规则：已暂停→已开启
        stateMachineService.assertAllowed(StateTransitions.SCENE_SUBSCRIPTION_PLAN,
                StateTransitions.ACTION_RESUME, plan.getStatus(), "续订计划");
        LocalDateTime nextRenewal = plan.getNextRenewalTime();
        // 恢复时下次续订时间顺延：从当前时刻起算（至少推到明晚 2 点），
        // 避免暂停跨越续订点后恢复瞬间立刻触发扣款
        LocalDateTime safeNext = LocalDateTime.now().plusDays(1).with(LocalTime.of(2, 0));
        if (nextRenewal == null || nextRenewal.isBefore(safeNext)) {
            nextRenewal = safeNext;
        }
        boolean resumed = lambdaUpdate()
                .eq(SubscriptionPlan::getId, id)
                .eq(SubscriptionPlan::getStatus, STATUS_PAUSED)
                .set(SubscriptionPlan::getStatus, STATUS_ACTIVE)
                .set(SubscriptionPlan::getNextRenewalTime, nextRenewal)
                .set(SubscriptionPlan::getPauseTime, null)
                .set(SubscriptionPlan::getPauseReason, null)
                .update();
        if (!resumed) {
            throw new BusinessException("计划状态已变更，请刷新后重试");
        }
        log.info("【自动续订】计划{} 已恢复，下次续订时间 {}", id, nextRenewal);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closePlan(Long id, boolean terminateNow, String reason) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        // 状态机规则：已开启/已暂停→已关闭
        stateMachineService.assertAllowed(StateTransitions.SCENE_SUBSCRIPTION_PLAN,
                StateTransitions.ACTION_CLOSE, plan.getStatus(), "续订计划");
        boolean closed = lambdaUpdate()
                .eq(SubscriptionPlan::getId, id)
                .in(SubscriptionPlan::getStatus, STATUS_ACTIVE, STATUS_PAUSED)
                .set(SubscriptionPlan::getStatus, STATUS_CLOSED)
                .set(SubscriptionPlan::getRemark, buildCloseRemark(plan, reason, terminateNow))
                .update();
        if (!closed) {
            throw new BusinessException("计划状态已变更，请刷新后重试");
        }
        // 终止时机：默认送完当前已生成的配送周期（未配送任务保留，已支付权益不损失）；
        // terminateNow=true 时同时取消未配送任务（立即终止，剩余期次线下退款）
        if (terminateNow) {
            int cancelled = deliveryTaskService.cancelPendingTasksForOrder(plan.getOriginalOrderId());
            log.info("【自动续订】计划{} 立即终止，取消未配送任务 {} 条", id, cancelled);
        } else {
            log.info("【自动续订】计划{} 已关闭，当前周期任务将执行完毕", id);
        }
    }

    private String buildCloseRemark(SubscriptionPlan plan, String reason, boolean terminateNow) {
        String base = StringUtils.hasText(reason) ? reason : "订阅终止";
        // 备注须与实际终止时机一致：立即终止时未配送任务已被取消，不能写成"送完为止"
        String suffix = terminateNow
                ? "（立即终止，未配送任务已取消，剩余期次线下退款）"
                : "（当前周期任务送完为止）";
        String existing = plan.getRemark();
        String merged = base + suffix;
        return StringUtils.hasText(existing) ? existing + "；" + merged : merged;
    }

    // ==================== 续订执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long triggerRenewal(Long id) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        // 状态机规则：仅已开启状态可续订（已暂停/已关闭的计划不会被定时任务或手动触发续订）
        stateMachineService.assertAllowed(StateTransitions.SCENE_SUBSCRIPTION_PLAN,
                StateTransitions.ACTION_RENEW, plan.getStatus(), "续订计划");
        // 调用订单服务续订（复制原订单+自动支付）
        Long newOrderId = orderInfoService.renewOrder(plan.getOriginalOrderId());

        // 更新计划：原订单ID指向新订单，续订时间顺延
        LocalDateTime now = LocalDateTime.now();
        boolean updated = lambdaUpdate()
                .eq(SubscriptionPlan::getId, id)
                .eq(SubscriptionPlan::getStatus, STATUS_ACTIVE)
                .set(SubscriptionPlan::getOriginalOrderId, newOrderId)
                .set(SubscriptionPlan::getLastRenewalTime, now)
                .set(SubscriptionPlan::getNextRenewalTime, now.plusMonths(1).with(LocalTime.of(2, 0)))
                .set(SubscriptionPlan::getReminderSent, 0)
                .update();
        if (!updated) {
            // 并发触发（手动+定时撞车）：续订订单已生成，这里只更新失败，记录告警不回滚订单
            log.warn("【自动续订】计划{} 续订订单已生成（{}）但计划时间链更新冲突", id, newOrderId);
        }

        log.info("【自动续订】计划{} 续订成功，新订单ID={}", id, newOrderId);
        return newOrderId;
    }

    @Override
    public int processDuePlans() {
        LocalDateTime now = LocalDateTime.now();
        // 暂停中的计划不参与续订：状态过滤只取已开启
        List<SubscriptionPlan> duePlans = baseMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getStatus, STATUS_ACTIVE)
                        .le(SubscriptionPlan::getNextRenewalTime, now));
        if (duePlans.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SubscriptionPlan plan : duePlans) {
            try {
                // 经自身代理调用，保证 triggerRenewal 的 @Transactional 生效（单计划失败独立回滚）
                self.triggerRenewal(plan.getId());
                count++;
            } catch (Exception e) {
                log.error("【自动续订】计划{} 续订失败：{}", plan.getId(), e.getMessage());
            }
        }
        return count;
    }

    // ==================== 内部工具 ====================

    private SubscriptionPlan getPlan(Long id) {
        SubscriptionPlan plan = getById(id);
        if (plan == null) {
            throw new BusinessException("续订计划不存在");
        }
        return plan;
    }

    /**
     * 校验当前用户是否有权访问该续订计划：
     * 家长仅能操作自己学生的计划；管理员与无登录上下文的定时续订任务不限制。
     */
    private void checkPlanAccess(SubscriptionPlan plan) {
        DataScope scope = dataScopeResolver.resolveQuietly();
        if (scope.isAll()) {
            return;
        }
        if (scope.getStudentId() != null && !scope.getStudentId().equals(plan.getStudentId())) {
            throw new BusinessException(403, "无权操作该续订计划");
        }
    }

    private List<SubscriptionPlanVO> convert(List<SubscriptionPlan> plans) {
        if (plans.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> studentIds = plans.stream().map(SubscriptionPlan::getStudentId).collect(Collectors.toSet());
        Set<Long> packageIds = plans.stream().map(SubscriptionPlan::getPackageId).collect(Collectors.toSet());
        Set<Long> orderIds = plans.stream().map(SubscriptionPlan::getOriginalOrderId).collect(Collectors.toSet());

        Map<Long, Student> studentMap = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, MealPackage> packageMap = mealPackageMapper.selectBatchIds(packageIds).stream()
                .collect(Collectors.toMap(MealPackage::getId, Function.identity()));
        Map<Long, OrderInfo> orderMap = orderInfoMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(OrderInfo::getId, Function.identity()));

        return plans.stream().map(plan -> {
            Student s = studentMap.get(plan.getStudentId());
            MealPackage p = packageMap.get(plan.getPackageId());
            OrderInfo o = orderMap.get(plan.getOriginalOrderId());
            return SubscriptionPlanVO.from(plan,
                    s == null ? null : s.getStudentName(),
                    p == null ? null : p.getPackageName(),
                    o == null ? null : o.getOrderNo());
        }).collect(Collectors.toList());
    }
}
