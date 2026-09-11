package com.milk.order.module.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 微信绑定请求 DTO（首次微信登录时绑定账号）：
 * <ul>
 *   <li>传 username/password：绑定到已有账号（校验密码，保留账号原有学生归属）</li>
 *   <li>不传 username：自动创建家长账号（realName 必填），并绑定关联学生</li>
 * </ul>
 */
@Data
public class WxBindRequest implements Serializable {

    /** wx.login 返回的临时登录凭证 */
    @NotBlank(message = "微信登录凭证 code 不能为空")
    private String code;

    /** 已有账号用户名（可选；不传则自动创建新账号） */
    private String username;

    /** 已有账号密码（绑定已有账号时必填） */
    private String password;

    /** 家长姓名（自动创建账号时必填） */
    private String realName;

    /** 手机号（可选） */
    private String phone;

    /** 关联学生 ID（自动创建账号时必填） */
    @NotNull(message = "请选择关联的学生")
    private Long studentId;
}
