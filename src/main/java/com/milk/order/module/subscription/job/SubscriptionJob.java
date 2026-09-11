package com.milk.order.module.subscription.job;

import com.milk.order.module.subscription.service.SubscriptionPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月度自动续订定时任务
 *
 * 每天凌晨 2:00 执行，扫描 next_renewal_time 已到期且状态为已开启的计划，
 * 自动复制原订单生成续订订单并模拟支付，更新计划的上次/下次续订时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionJob {

    private final SubscriptionPlanService subscriptionPlanService;

    /**
     * 每天凌晨 2:00 执行自动续订
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void autoRenewal() {
        log.info("【自动续订任务】开始执行");
        try {
            int count = subscriptionPlanService.processDuePlans();
            log.info("【自动续订任务】执行完成，共处理 {} 个计划", count);
        } catch (Exception e) {
            log.error("【自动续订任务】执行异常", e);
        }
    }
}
