package com.milk.order.module.auth.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 登录响应 VO
 */
@Data
public class LoginResponse implements Serializable {

    private String token;

    private Long userId;

    private String username;

    private String realName;

    private List<String> roles;
}
