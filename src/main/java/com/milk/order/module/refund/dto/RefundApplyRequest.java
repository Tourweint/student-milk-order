package com.milk.order.module.refund.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 退款申请请求。
 *
 * <p>申请盒数只是参考值：实际退款盒数与金额一律在执行时刻按 R1/R2 口径重新计算，
 * 避免"申请→执行"窗口期内任务继续配送导致快照失真。</p>
 */
@Data
public class RefundApplyRequest implements Serializable {

    /** 申请退款盒数（参考值，须 &gt; 0） */
    @NotNull(message = "申请盒数不能为空")
    @Min(value = 1, message = "申请盒数必须大于 0")
    private Integer applyBoxCount;

    /** 申请原因（可选） */
    @Size(max = 200, message = "申请原因不能超过 200 字")
    private String reason;
}
