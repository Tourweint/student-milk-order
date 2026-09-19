package com.milk.order.process.pending;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 过程自愈待办（实时通道的载体）。
 *
 * <p>它把「子过程状态已经变了，父过程需要重新聚合」这件事从**隐式约定**（调用方记得调用聚合出口）
 * 变成**显式数据**（事务内落一条待办）。因此即使某条业务路径漏掉了聚合调用、或聚合时被规则表临时挡住，
 * 待办仍然存在，秒级消费者会把它补上。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("process_pending_task")
public class ProcessPendingTask extends BaseEntity {

    /** 待补偿的父过程场景，如 ORDER */
    private String scene;

    /** 父过程表名，如 order_info */
    private String entityType;

    /** 父过程主键 */
    private Long entityId;

    /** 业务单号 */
    private String bizNo;

    /** 触发动作（子过程终态动作），与 scene/entity 共同构成去重键 */
    private String triggerAction;

    /** 0-待处理，1-已处理，2-已放弃（超重试上限，转兜底通道） */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可处理时间（指数退避） */
    private LocalDateTime nextRetryTime;

    /** 最近一次失败原因 */
    private String lastError;
}
