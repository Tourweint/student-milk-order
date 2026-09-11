package com.milk.order.module.delivery.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 签收请求 DTO
 */
@Data
public class SignRequest implements Serializable {

    /** 配送记录 ID */
    @NotNull(message = "配送记录不能为空")
    private Long recordId;

    /** 签收人（学生姓名或班主任） */
    private String signPerson;

    /** 备注 */
    private String remark;
}
