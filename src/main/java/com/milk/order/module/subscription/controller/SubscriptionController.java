package com.milk.order.module.subscription.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.subscription.dto.CreateSubscriptionRequest;
import com.milk.order.module.subscription.dto.PausePlanRequest;
import com.milk.order.module.subscription.entity.SubscriptionPlan;
import com.milk.order.module.subscription.service.SubscriptionPlanService;
import com.milk.order.module.subscription.vo.SubscriptionPlanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 月度自动续订控制器
 *
 * 接口清单：
 * - GET    /api/subscription/list           续订计划分页（状态筛选）
 * - GET    /api/subscription/{id}           续订计划详情
 * - POST   /api/subscription                 开启自动续订（基于订单创建计划）
 * - PUT    /api/subscription                  修改续订计划（备注/周期）
 * - PUT    /api/subscription/pause/{id}      暂停续订（已开启→已暂停）
 * - PUT    /api/subscription/resume/{id}     恢复续订（已暂停→已开启，续订时间顺延）
 * - DELETE /api/subscription/{id}            关闭自动续订（终止订阅；?terminateNow=true 同时取消未配送任务）
 * - POST   /api/subscription/trigger/{id}    手动触发续订（测试用）
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionPlanService subscriptionPlanService;

    @GetMapping("/list")
    public ApiResponse<PageResult<SubscriptionPlanVO>> list(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Integer status) {
        IPage<SubscriptionPlanVO> page = subscriptionPlanService.pagePlans(pageNum, pageSize, status);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SubscriptionPlanVO> getById(@PathVariable Long id) {
        return ApiResponse.success(subscriptionPlanService.getPlanDetail(id));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateSubscriptionRequest request) {
        Long planId = subscriptionPlanService.createPlan(request);
        return ApiResponse.success(planId);
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody SubscriptionPlan plan) {
        subscriptionPlanService.updatePlan(plan);
        return ApiResponse.success();
    }

    /** 暂停续订：暂停期间不生成新订单；已生成任务默认保留，可选取消 */
    @PutMapping("/pause/{id}")
    public ApiResponse<Void> pause(@PathVariable Long id,
                                   @RequestBody(required = false) PausePlanRequest request) {
        subscriptionPlanService.pausePlan(id,
                request == null ? null : request.getReason(),
                request == null || request.getKeepPendingTasks() == null || request.getKeepPendingTasks());
        return ApiResponse.success();
    }

    /** 恢复续订：下次续订时间顺延，不会恢复瞬间立刻扣款 */
    @PutMapping("/resume/{id}")
    public ApiResponse<Void> resume(@PathVariable Long id) {
        subscriptionPlanService.resumePlan(id);
        return ApiResponse.success();
    }

    /** 关闭自动续订（终止订阅）：默认送完当前周期；terminateNow=true 立即终止并取消未配送任务 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(required = false, defaultValue = "false") Boolean terminateNow,
                                    @RequestParam(required = false) String reason) {
        subscriptionPlanService.closePlan(id, Boolean.TRUE.equals(terminateNow), reason);
        return ApiResponse.success();
    }

    @PostMapping("/trigger/{id}")
    public ApiResponse<Long> trigger(@PathVariable Long id) {
        Long newOrderId = subscriptionPlanService.triggerRenewal(id);
        return ApiResponse.success(newOrderId);
    }
}
