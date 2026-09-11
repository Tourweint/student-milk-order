package com.milk.order.module.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 微信登录请求 DTO：wx.login 获取的 code 换取 openid 并登录
 */
@Data
public class WxLoginRequest implements Serializable {

    /** wx.login 返回的临时登录凭证 */
    @NotBlank(message = "微信登录凭证 code 不能为空")
    private String code;
}
