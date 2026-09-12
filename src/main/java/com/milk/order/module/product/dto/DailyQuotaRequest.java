package com.milk.order.module.product.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 每日机动配额设置请求
 */
@Data
public class DailyQuotaRequest {

    /** 配额日期 */
    @NotNull(message = "请选择配额日期")
    private LocalDate quotaDate;

    /** 当日机动总盒数 */
    @NotNull(message = "请填写机动盒数")
    @Min(value = 0, message = "机动盒数不能为负")
    private Integer totalQuota;

    /** 备注（可选） */
    private String remark;
}
