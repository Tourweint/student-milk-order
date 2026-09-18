package com.milk.order.module.order.job;

import com.milk.order.common.enums.OrderStatus;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待支付订单超时自动取消任务
 *
 * 每 5 分钟扫描一次：待支付状态超过配置时长（sys_config: order.pay.timeout.minutes，默认 15 分钟）
 * 的订单自动取消（待支付 → 已取消）。
 * 取消前会先经模拟微信查单对账兜底——用户已实际付款但回调丢失的订单会被补偿落账而不是取消；
 * 超时锚点取最近一次待支付流水（预下单）时间，避免误杀刚重新发起支付的订单。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderTimeoutCancelJob {

    private final OrderInfoService orderInfoService;
    private final SysConfigService sysConfigService;

    /** 单批最大处理量，防止历史脏数据把任务拖死 */
    private static final int BATCH_LIMIT = 200;

    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void autoCancelTimeoutOrders() {
        int timeoutMinutes = sysConfigService.getInt("order.pay.timeout.minutes", 15);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<OrderInfo> candidates = orderInfoService.lambdaQuery()
                .eq(OrderInfo::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                .lt(OrderInfo::getCreateTime, deadline)
                .last("LIMIT " + BATCH_LIMIT)
                .list();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("[支付超时任务] 发现 {} 个超时候选订单（超时阈值 {} 分钟），开始处理", candidates.size(), timeoutMinutes);
        int cancelled = 0;
        for (OrderInfo order : candidates) {
            try {
                // 返回 true 才表示真正取消；未到锚点/已扣款转补偿/被规则禁止均返回 false，不计入取消数
                if (orderInfoService.cancelTimeoutOrder(order.getId(), timeoutMinutes)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.error("[支付超时任务] 订单 {} 处理失败：{}", order.getOrderNo(), e.getMessage());
            }
        }
        log.info("[支付超时任务] 本轮处理完成，候选 {} 个，实际取消 {} 个", candidates.size(), cancelled);
    }
}
