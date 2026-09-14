package com.milk.order.module.order.job;

import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付对账补偿任务
 *
 * 每 5 分钟扫描存在待支付流水且已过在途窗口（2 分钟）的订单，主动向模拟微信侧查单：
 * 已支付但回调丢失的订单直接补偿落账（对应真实链路微信支付的「查单」接口）。
 * 可通过系统参数 sys_config: order.pay.reconcile.enabled 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private final OrderInfoService orderInfoService;
    private final SysConfigService sysConfigService;

    @Scheduled(fixedDelay = 300_000L, initialDelay = 180_000L)
    public void reconcile() {
        if (!sysConfigService.getBool("order.pay.reconcile.enabled", true)) {
            return;
        }
        try {
            int count = orderInfoService.reconcilePendingPayments();
            if (count > 0) {
                log.info("[支付对账] 本轮补偿落账 {} 个订单", count);
            }
        } catch (Exception e) {
            log.error("[支付对账] 执行异常", e);
        }
    }
}
