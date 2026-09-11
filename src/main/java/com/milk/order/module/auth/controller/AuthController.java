package com.milk.order.module.auth.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.dto.WxBindRequest;
import com.milk.order.module.auth.dto.WxLoginRequest;
import com.milk.order.module.auth.service.AuthService;
import com.milk.order.module.auth.vo.LoginResponse;
import com.milk.order.module.auth.vo.WxLoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 认证控制器（登录/注册/微信登录/当前用户）
 *
 * 接口清单：
 * - POST /api/auth/login     登录
 * - POST /api/auth/register  注册
 * - POST /api/auth/wx-login  微信登录（code 换 openid，已绑定直接登录）
 * - POST /api/auth/wx-bind   微信绑定（绑定已有账号或自动创建家长账号）
 * - GET  /api/auth/me        获取当前登录用户信息
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
     * 微信登录：wx.login 获取 code 后调用；已绑定账号直接返回 token，未绑定返回 bound=false
     */
    @PostMapping("/wx-login")
    public ApiResponse<WxLoginVO> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return ApiResponse.success(authService.wxLogin(request));
    }

    /**
     * 微信绑定：首次微信登录时绑定已有账号或自动创建家长账号
     */
    @PostMapping("/wx-bind")
    public ApiResponse<WxLoginVO> wxBind(@Valid @RequestBody WxBindRequest request) {
        return ApiResponse.success(authService.wxBind(request));
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ApiResponse<LoginResponse> me() {
        return ApiResponse.success(authService.getCurrentUser());
    }
}
