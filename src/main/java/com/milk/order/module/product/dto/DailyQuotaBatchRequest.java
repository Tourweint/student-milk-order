package com.milk.order.module.product.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 每日机动配额批量设置请求（某日各品种配额一起提交）
 */
@Data
public class DailyQuotaBatchRequest {

    /** 配额日期 */
    @NotNull(message = "请选择配额日期")
    private LocalDate quotaDate;

    /** 各品种配额 */
    @Valid
    private List<Item> items;

    /** 备注（可选） */
    private String remark;

    @Data
    public static class Item {

        /** 奶品 ID */
        @NotNull(message = "奶品不能为空")
        private Long productId;

        /** 当日该品种机动总盒数 */
        @NotNull(message = "请填写机动盒数")
        private Integer totalQuota;
    }
}
