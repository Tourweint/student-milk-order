package com.milk.order.module.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 过敏/禁忌预检命中项（**软警示**：只提示、不拦截）
 *
 * <p>设计依据（"警示优于拦截"）：孩子的禁忌标签来自管理端维护，可能滞后、可能不准，
 * 也可能家长就是要买（例如"香精敏感"但只有这一款）。系统强行拦截会直接阻断交易；
 * 而把"命中了什么、为什么命中"显式告诉家长，让他自己决定，既保住了知情权，也不越权替人做决定。
 * 因此本预检**只返回命中清单**，是否继续下单由用户确认。</p>
 */
@Data
public class AllergyWarningVO implements Serializable {

    /** 命中的奶品 */
    private Long productId;

    private String productName;

    /** 命中的过敏原编码（学生禁忌 ∩ 奶品过敏原） */
    private List<String> allergens;

    /** 命中过敏原的中文（奶品侧口径） */
    private List<String> allergenTexts;

    /** 一句话提示（前端可直接展示，避免两端各自拼中文） */
    private String message;
}
