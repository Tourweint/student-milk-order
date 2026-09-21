package com.milk.order.module.refund.vo;

import com.milk.order.module.delivery.vo.RefundableTaskVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款预览（只读探测，不落库）：当前可退期次、可退盒数与预估金额。
 *
 * <p>与执行退款共用同一套计算方法（见 {@code RefundOrderServiceImpl} 的金额计算私有方法），
 * 保证"预览看到多少"与"实际退多少"口径一致；差异只可能来自预览到执行之间的并发配送。</p>
 */
@Data
public class RefundPreviewVO {

    private Long orderId;

    private String orderNo;

    private Integer orderStatus;

    private String orderStatusText;

    /** 合同总盒数（支付时快照，退款金额分母基准） */
    private Integer contractTotalBoxes;

    /** 当前可退盒数 */
    private Integer refundableBoxes;

    /** 当前可退期次明细 */
    private List<RefundableTaskVO> refundableTasks;

    /** 该订单已退盒数（累计） */
    private Integer refundedBoxes;

    /** 该订单已退金额（元） */
    private BigDecimal refundedAmount;

    /** 预估本次可退金额（元） */
    private BigDecimal estimatedAmount;

    /** 是否存在进行中的退款单（有则家长不能重复申请） */
    private boolean hasActiveRefund;
}
