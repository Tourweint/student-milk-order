package com.milk.order.module.auth.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 微信登录响应 VO
 * <ul>
 *   <li>bound=false：微信未绑定账号，前端引导用户走绑定流程（wx-bind）</li>
 *   <li>bound=true：登录成功，返回 token 与用户信息</li>
 * </ul>
 */
@Data
public class WxLoginVO implements Serializable {

    /** 是否已绑定账号 */
    private boolean bound;

    /** 微信 openid */
    private String openid;

    private String token;

    private Long userId;

    private String username;

    private String realName;

    private List<String> roles;
}
