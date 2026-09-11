package com.milk.order.module.subscription.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.subscription.dto.CreateSubscriptionRequest;
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
 * - DELETE /api/subscription/{id}             关闭自动续订
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

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        subscriptionPlanService.closePlan(id);
        return ApiResponse.success();
    }

    @PostMapping("/trigger/{id}")
    public ApiResponse<Long> trigger(@PathVariable Long id) {
        Long newOrderId = subscriptionPlanService.triggerRenewal(id);
        return ApiResponse.success(newOrderId);
    }
}
