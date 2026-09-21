package com.milk.order.module.delivery.job;

import com.milk.order.module.delivery.service.DeliveryTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配送签收自动兜底任务
 *
 * 每天 00:30 扫描一次：配送日期早于当天、任务已送出（配送中）但记录仍未签收的配送记录，
 * 自动签收（签收人标记"系统自动签收"），复用人工签收共用流程（状态机 CAS + 生成营养摄入 + 订单全终态自动完成）。
 *
 * 设计口径：配送站已送出 + 老师未拒收 + 超过当日签收窗口 = 默认视为已签收（与"签收即视为当日饮用"的
 * 营养估算口径一致）。拒收仍是老师当场的即时人工动作，自动兜底只处理"未签收且未拒收"的记录，不误伤。
 * 当天送出的记录留给老师当天签收，不进入自动兜底窗口。
 *
 * <p><b>一个必须显式排除的例外</b>：奶站可以申报「当日未送达」（`delivery_undelivered_report`）。
 * 本任务的候选集来自**信息态**（配送站点了"今日已送出"），而申报表达的是**物理态**（奶实际没到校）；
 * 若把申报过的任务也按"超时未签收"签掉，就会凭空生成虚假签收与营养摄入。因此被申报的任务
 * 不进候选集（`listExpiredAutoSignRecordIds` 过滤），单条入口 `autoSignOne` 也再判一次
 * （候选集是分批取的，两次取之间可能新增申报）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeliveryAutoSignJob {

    private final DeliveryTaskService deliveryTaskService;

    /** 单批最大处理量（防止历史脏数据把任务拖死，与 Service 内上限一致） */
    private static final int BATCH_LIMIT = 200;

    @Scheduled(cron = "0 30 0 * * ?")
    public void autoSignExpiredRecords() {
        List<Long> candidateIds = deliveryTaskService.listExpiredAutoSignRecordIds(BATCH_LIMIT);
        if (candidateIds.isEmpty()) {
            return;
        }
        log.info("[自动签收任务] 发现 {} 条超时未签收配送记录，开始自动签收", candidateIds.size());
        int signed = 0;
        for (Long recordId : candidateIds) {
            try {
                deliveryTaskService.autoSignOne(recordId);
                signed++;
            } catch (Exception e) {
                // 单条失败不影响其余；CAS 冲突（并发签收/拒收）时 doSign 抛业务异常，此处按已处理跳过
                log.error("[自动签收任务] 配送记录 {} 自动签收失败：{}", recordId, e.getMessage());
            }
        }
        log.info("[自动签收任务] 本轮完成，候选 {} 条，成功自动签收 {} 条", candidateIds.size(), signed);
    }
}
