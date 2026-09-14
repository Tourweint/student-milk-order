package com.milk.order.module.subscription.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 暂停续订计划请求
 */
@Data
public class PausePlanRequest implements Serializable {

    /** 暂停原因（选填） */
    private String reason;

    /**
     * 是否保留已生成的待配送任务：
     * true（默认）= 已支付权益保留，未配送任务照常配送；
     * false = 同时取消未配送任务（该期不送）
     */
    private Boolean keepPendingTasks;
}
