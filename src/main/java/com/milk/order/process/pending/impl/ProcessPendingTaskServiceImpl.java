package com.milk.order.process.pending.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.milk.order.process.mapper.ProcessPendingTaskMapper;
import com.milk.order.process.pending.ProcessPendingTask;
import com.milk.order.process.pending.ProcessPendingTaskService;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实时通道待办服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPendingTaskServiceImpl implements ProcessPendingTaskService {

    /** 待处理 */
    public static final int STATUS_PENDING = 0;
    /** 已处理 */
    public static final int STATUS_DONE = 1;
    /** 已放弃（超重试上限，交由定时兜底通道） */
    public static final int STATUS_ABANDONED = 2;

    /** 首次重试等待秒数（退避基数） */
    private static final long BASE_BACKOFF_SECONDS = 5L;
    /** 退避上限，避免失败项被判“过期”太久拖慢收敛 */
    private static final long MAX_BACKOFF_SECONDS = 300L;

    private final ProcessPendingTaskMapper pendingTaskMapper;
    private final IdempotencyGuard idempotencyGuard;

    @Override
    public void enqueue(String scene, String entityType, Long entityId, String bizNo, String triggerAction) {
        if (entityId == null || !StringUtils.hasText(scene) || !StringUtils.hasText(triggerAction)) {
            return;
        }
        ProcessPendingTask task = new ProcessPendingTask();
        task.setScene(scene);
        task.setEntityType(entityType);
        task.setEntityId(entityId);
        task.setBizNo(bizNo);
        task.setTriggerAction(triggerAction);
        task.setStatus(STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryTime(LocalDateTime.now());
        // 唯一键仲裁：并发/重复触发只会留下一条待处理记录（冲突被翻译为“已排队”）
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(() -> pendingTaskMapper.insert(task));
        if (!inserted) {
            log.debug("[实时自愈] 待办已存在，跳过入队：scene={}, entityId={}, trigger={}",
                    scene, entityId, triggerAction);
        }
    }

    @Override
    public List<ProcessPendingTask> claimDue(int limit) {
        return pendingTaskMapper.selectList(new LambdaQueryWrapper<ProcessPendingTask>()
                .eq(ProcessPendingTask::getStatus, STATUS_PENDING)
                .and(w -> w.isNull(ProcessPendingTask::getNextRetryTime)
                        .or().le(ProcessPendingTask::getNextRetryTime, LocalDateTime.now()))
                .orderByAsc(ProcessPendingTask::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public void markDone(Long id) {
        pendingTaskMapper.update(null, new LambdaUpdateWrapper<ProcessPendingTask>()
                .eq(ProcessPendingTask::getId, id)
                .set(ProcessPendingTask::getStatus, STATUS_DONE)
                .set(ProcessPendingTask::getNextRetryTime, null)
                .set(ProcessPendingTask::getLastError, null));
    }

    @Override
    public boolean markRetry(ProcessPendingTask task, String error, int maxRetry) {
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        boolean abandoned = retry >= Math.max(1, maxRetry);
        LocalDateTime nextRetry = abandoned ? null : LocalDateTime.now().plusSeconds(backoffSeconds(retry));
        pendingTaskMapper.update(null, new LambdaUpdateWrapper<ProcessPendingTask>()
                .eq(ProcessPendingTask::getId, task.getId())
                .set(ProcessPendingTask::getRetryCount, retry)
                .set(ProcessPendingTask::getStatus, abandoned ? STATUS_ABANDONED : STATUS_PENDING)
                .set(ProcessPendingTask::getNextRetryTime, nextRetry)
                .set(ProcessPendingTask::getLastError, truncate(error)));
        return abandoned;
    }

    @Override
    public int purgeHandled(int retentionDays) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        // 只清理已处理/已放弃的行：待处理行（status=0）不能被清理，
        // 且唯一键含 status，因此逻辑删除已处理行不会阻塞同一业务键的新待办入队
        return pendingTaskMapper.delete(new LambdaQueryWrapper<ProcessPendingTask>()
                .ne(ProcessPendingTask::getStatus, STATUS_PENDING)
                .lt(ProcessPendingTask::getCreateTime, before));
    }

    /** 退避：5s、10s、20s… 上限 300s */
    private long backoffSeconds(int retry) {
        long seconds = BASE_BACKOFF_SECONDS << Math.min(retry - 1, 16);
        return Math.min(seconds, MAX_BACKOFF_SECONDS);
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 250 ? error.substring(0, 250) : error;
    }
}
