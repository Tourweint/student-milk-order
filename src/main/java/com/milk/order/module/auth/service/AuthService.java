package com.milk.order.module.auth.service;

import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.vo.LoginResponse;

public interface AuthService {

    /**
     * 登录：校验用户名密码，生成 JWT Token
     */
    LoginResponse login(LoginRequest request);

    /**
     * 注册：创建用户并分配角色
     */
    void register(RegisterRequest request);

    /**
     * 获取当前登录用户信息
     */
    LoginResponse getCurrentUser();
}
