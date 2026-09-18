package com.milk.order.module.order.job;

import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 业务过程聚合对账补偿任务（兜底层）。
 *
 * <p>正常路径下，父订单状态由子过程驱动：任务开始配送时联动订单进入「配送中」，
 * 任务全部到达终态时聚合为「已完成」。但异步链路存在两条会丢失推进的缝隙：</p>
 * <ul>
 *   <li>任务已送出，但订单联动因进程中断/异常未被写入；</li>
 *   <li>最后一条子任务已终态，但聚合回调因异常未被写入。</li>
 * </ul>
 *
 * <p>本任务定期扫描这两类「父状态与子过程集合不一致」的漂移并补偿修复，
 * 使系统不依赖「所有推进都恰好成功」，而是具备可检测、可恢复的最终一致性。
 * 补偿复用过程层统一迁移出口与聚合出口，天然幂等，可重复执行。
 * 可通过系统参数 {@code order.process.reconcile.enabled} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderProcessReconcileJob {

    private final OrderInfoService orderInfoService;
    private final SysConfigService sysConfigService;

    /** 单轮单类最大处理量，防止历史脏数据把任务拖死 */
    private static final int BATCH_LIMIT = 200;

    @Scheduled(fixedDelay = 600_000L, initialDelay = 300_000L)
    public void reconcile() {
        if (!sysConfigService.getBool("order.process.reconcile.enabled", true)) {
            return;
        }
        try {
            int repaired = orderInfoService.reconcileOrderAggregation(BATCH_LIMIT);
            if (repaired > 0) {
                log.info("[过程对账] 本轮修复父子状态漂移 {} 个订单", repaired);
            }
        } catch (Exception e) {
            log.error("[过程对账] 执行异常", e);
        }
    }
}
