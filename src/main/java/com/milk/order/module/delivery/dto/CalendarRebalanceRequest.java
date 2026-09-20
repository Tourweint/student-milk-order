package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 配送日历重排请求（合并周末 + 停送日，仅管理员）。
 */
@Data
public class CalendarRebalanceRequest implements Serializable {

    /** 开始日期（yyyy-MM-dd，覆盖学期配送周期） */
    @NotBlank(message = "开始日期不能为空")
    private String startDate;

    /** 结束日期（yyyy-MM-dd，须不早于开始日期） */
    @NotBlank(message = "结束日期不能为空")
    private String endDate;
}
