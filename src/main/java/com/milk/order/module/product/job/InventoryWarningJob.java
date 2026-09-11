package com.milk.order.module.product.job;

import com.milk.order.module.product.service.InventoryService;
import com.milk.order.module.product.vo.InventoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存预警定时任务
 *
 * 每天早上 8:00 执行，检查库存低于预警阈值的奶品并记录预警日志。
 * （毕设阶段不接入真实消息推送，仅输出日志；预警列表同时可通过接口实时查询）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryWarningJob {

    private final InventoryService inventoryService;

    /**
     * 每天早上 8:00 检查库存预警
     */
    @Scheduled(cron = "0 0 8 * * ?")
    public void checkInventoryWarning() {
        List<InventoryVO> warnings = inventoryService.listWarning();
        if (warnings.isEmpty()) {
            log.info("【库存预警任务】库存充足，无预警奶品");
            return;
        }
        log.warn("【库存预警任务】共 {} 种奶品低于预警阈值：", warnings.size());
        for (InventoryVO vo : warnings) {
            log.warn("【库存预警】奶品「{}」当前库存 {}，阈值 {}，库位 {}",
                    vo.getProductName(), vo.getQuantity(), vo.getWarningThreshold(), vo.getWarehouseLocation());
        }
    }
}
