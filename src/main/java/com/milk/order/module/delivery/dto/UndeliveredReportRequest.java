package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 奶站「当日未送达申报」请求
 *
 * <p>语义：该任务已被标记"已送出"（配送中），但物理上没有送到——申报后任务被排除出自动签收候选集，
 * 由管理端人工跟进。申报本身**不改任何状态**。</p>
 */
@Data
public class UndeliveredReportRequest implements Serializable {

    /** 配送任务 ID */
    @NotNull(message = "配送任务不能为空")
    private Long taskId;

    /** 未送达原因（奶站填写，必须具体，便于人工核实） */
    @NotBlank(message = "请填写未送达原因")
    @Size(max = 255, message = "未送达原因不能超过 255 字")
    private String reason;
}
