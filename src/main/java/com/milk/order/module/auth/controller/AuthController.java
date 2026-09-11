package com.milk.order.module.auth.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.service.AuthService;
import com.milk.order.module.auth.vo.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 认证控制器（登录/注册/当前用户）
 *
 * 接口清单：
 * - POST /api/auth/login    登录
 * - POST /api/auth/register 注册
 * - GET  /api/auth/me       获取当前登录用户信息
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success();
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ApiResponse<LoginResponse> me() {
        return ApiResponse.success(authService.getCurrentUser());
    }
}
