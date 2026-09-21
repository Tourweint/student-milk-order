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

        /**
         * 可选：当日该品种到货批次号（批次追溯钩子，仅作标注，不参与扣减/结转）。
         * 传空表示清除该池子的批次标注。
         */
        private String batchNo;
    }
}
