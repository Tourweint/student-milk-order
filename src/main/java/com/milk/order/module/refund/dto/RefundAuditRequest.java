package com.milk.order.module.refund.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/** 退款审核请求：通过 → 已审核待退款(2)；拒绝 → 已拒绝(4)（可重新申请） */
@Data
public class RefundAuditRequest implements Serializable {

    /** 是否通过审核 */
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    /** 审核意见（可选） */
    @Size(max = 200, message = "审核意见不能超过 200 字")
    private String remark;
}
