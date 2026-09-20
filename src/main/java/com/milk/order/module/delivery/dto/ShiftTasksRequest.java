package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

/**
 * 配送日平移请求
 */
@Data
public class ShiftTasksRequest implements Serializable {

    /** 待平移的配送任务 ID 列表（只处理其中「待配送」的任务） */
    @NotEmpty(message = "请选择要平移的配送任务")
    private List<Long> taskIds;

    /** 目标配送日期（yyyy-MM-dd） */
    @NotBlank(message = "目标配送日期不能为空")
    private String targetDate;
}
