package com.milk.order.module.user.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 重置密码请求 DTO
 */
@Data
public class UserResetPasswordRequest implements Serializable {

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
