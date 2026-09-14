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

    /** 开启自动续订（基于订单创建计划，设置下次续订时间）；同一学生仅允许一个生效计划（开启/暂停） */
    Long createPlan(CreateSubscriptionRequest request);

    /** 修改续订计划 */
    void updatePlan(SubscriptionPlan plan);

    /** 暂停续订（已开启→已暂停）：暂停期间不生成新续订订单；已生成任务按 keepPendingTasks 决定保留或取消 */
    void pausePlan(Long id, String reason, boolean keepPendingTasks);

    /** 恢复续订（已暂停→已开启）：下次续订时间顺延，避免恢复瞬间立刻触发大额扣款 */
    void resumePlan(Long id);

    /** 关闭自动续订（终止订阅）：terminateNow=false 默认送完当前周期（未配送任务保留）；true 同时取消未配送任务 */
    void closePlan(Long id, boolean terminateNow, String reason);

    /** 手动触发续订（测试用，立即生成续订订单并自动支付） */
    Long triggerRenewal(Long id);

    /** 处理所有到期的续订计划（定时任务调用），返回处理数量 */
    int processDuePlans();
}
