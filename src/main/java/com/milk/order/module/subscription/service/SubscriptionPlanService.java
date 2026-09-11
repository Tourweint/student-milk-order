package com.milk.order.module.subscription.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.subscription.dto.CreateSubscriptionRequest;
import com.milk.order.module.subscription.entity.SubscriptionPlan;
import com.milk.order.module.subscription.vo.SubscriptionPlanVO;

public interface SubscriptionPlanService extends IService<SubscriptionPlan> {

    /** 续订计划分页（状态筛选，回填关联名称） */
    IPage<SubscriptionPlanVO> pagePlans(Long pageNum, Long pageSize, Integer status);

    /** 计划详情 */
    SubscriptionPlanVO getPlanDetail(Long id);

    /** 开启自动续订（基于订单创建计划，设置下次续订时间） */
    Long createPlan(CreateSubscriptionRequest request);

    /** 修改续订计划 */
    void updatePlan(SubscriptionPlan plan);

    /** 关闭自动续订（status=0） */
    void closePlan(Long id);

    /** 手动触发续订（测试用，立即生成续订订单并更新计划时间） */
    Long triggerRenewal(Long id);

    /** 处理所有到期的续订计划（定时任务调用），返回处理数量 */
    int processDuePlans();
}
