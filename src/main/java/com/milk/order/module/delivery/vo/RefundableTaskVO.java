package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 可退期次（退款域 R1 口径的探测结果，只读）。
 *
 * <p>「期次」= 一条配送任务。可退期次 = 待配送(1) ∪ 缺货取消（已取消且签收记录为拒收、未写拒收原因分类）。
 * 该判定必须与 {@code INV_TASK_COMPENSATION} 同判据，否则把"真拒收（已补送）"当成可退会重复退钱。</p>
 */
@Data
public class RefundableTaskVO {

    private Long taskId;

    private String taskNo;

    private Long productId;

    private String productName;

    private LocalDate deliveryDate;

    /** 任务当前盒数（可能含拒收补送的免费盒） */
    private Integer quantity;

    /**
     * 可退盒数 = 任务盒数 − 该任务上的拒收补送盒数。
     *
     * <p>补送盒是商家免费补偿（不额外收费），计入退款分子会让家长多退钱；纯补送任务可退 0 盒，
     * 不进可退集（避免产生"0 元退款单"）。</p>
     */
    private Integer refundableBoxes;

    /** true=待配送（执行时需 CAS 作废）；false=缺货取消（本就终态，直接计入可退集） */
    private boolean pending;

    /** 展示用状态文案 */
    private String statusText;
}
