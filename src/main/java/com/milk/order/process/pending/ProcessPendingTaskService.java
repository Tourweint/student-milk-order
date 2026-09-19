package com.milk.order.process.pending;

import java.util.List;

/**
 * 过程自愈待办服务（实时通道）。
 *
 * <p>使用约定：<b>入队必须在子过程状态变更的同一事务内完成</b>——
 * 这样「子状态已变」与「父过程待聚合」要么一起提交、要么一起回滚，
 * 待办本身不会成为新的不一致来源。</p>
 */
public interface ProcessPendingTaskService {

    /**
     * 入队一条待办（幂等）。
     *
     * <p>去重键为 (scene, entityId, triggerAction, status)：同一主体、同一触发动作只保留一条待处理记录，
     * 因此批量操作（一次批量送出 78 条任务）只会产生一行待办，不会把待办表冲爆。</p>
     */
    void enqueue(String scene, String entityType, Long entityId, String bizNo, String triggerAction);

    /** 取一批到期待办（含退避后到期的重试项），按 id 升序 */
    List<ProcessPendingTask> claimDue(int limit);

    /** 标记为已处理 */
    void markDone(Long id);

    /**
     * 记录一次失败并按指数退避安排下一次重试。
     *
     * @return true 表示已达重试上限、待办被放弃（转由定时兜底通道兜住）
     */
    boolean markRetry(ProcessPendingTask task, String error, int maxRetry);

    /** 清理已处理/已放弃的历史待办（保留最近若干天，避免表无限增长） */
    int purgeHandled(int retentionDays);
}
