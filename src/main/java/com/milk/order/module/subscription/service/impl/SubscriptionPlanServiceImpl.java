package com.milk.order.module.subscription.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
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
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final DataScopeResolver dataScopeResolver;

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

    // ==================== 创建/修改/关闭 ====================

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
        // 同一学生同一原订单不能重复开启
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getOriginalOrderId, request.getOriginalOrderId())
                .eq(SubscriptionPlan::getStatus, 1));
        if (exists != null && exists > 0) {
            throw new BusinessException("该订单已开启自动续订");
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
        plan.setStatus(1);
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
    public void closePlan(Long id) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        plan.setStatus(0);
        updateById(plan);
    }

    // ==================== 续订执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long triggerRenewal(Long id) {
        SubscriptionPlan plan = getPlan(id);
        checkPlanAccess(plan);
        if (plan.getStatus() != 1) {
            throw new BusinessException("仅已开启的计划可续订");
        }
        // 调用订单服务续订（复制原订单+自动支付）
        Long newOrderId = orderInfoService.renewOrder(plan.getOriginalOrderId());

        // 更新计划：原订单ID指向新订单，续订时间顺延
        LocalDateTime now = LocalDateTime.now();
        plan.setOriginalOrderId(newOrderId);
        plan.setLastRenewalTime(now);
        plan.setNextRenewalTime(now.plusMonths(1).with(LocalTime.of(2, 0)));
        plan.setReminderSent(0);
        updateById(plan);

        log.info("【自动续订】计划{} 续订成功，新订单ID={}", id, newOrderId);
        return newOrderId;
    }

    @Override
    public int processDuePlans() {
        LocalDateTime now = LocalDateTime.now();
        List<SubscriptionPlan> duePlans = baseMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getStatus, 1)
                        .le(SubscriptionPlan::getNextRenewalTime, now));
        if (duePlans.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SubscriptionPlan plan : duePlans) {
            try {
                triggerRenewal(plan.getId());
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
