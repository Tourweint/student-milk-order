package com.milk.order.module.auth.service;

import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.dto.WxBindRequest;
import com.milk.order.module.auth.dto.WxLoginRequest;
import com.milk.order.module.auth.vo.LoginResponse;
import com.milk.order.module.auth.vo.StudentBindVO;
import com.milk.order.module.auth.vo.WxLoginVO;

import java.util.List;

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

    /**
     * 微信登录：code 换 openid；已绑定账号直接登录，未绑定返回 bound=false 引导绑定
     */
    WxLoginVO wxLogin(WxLoginRequest request);

    /**
     * 微信绑定：绑定已有账号或自动创建家长账号，返回登录结果
     */
    WxLoginVO wxBind(WxBindRequest request);

    /**
     * 绑定流程学生搜索（免认证）：按学号/姓名关键词返回学生最小信息，最多 10 条
     */
    List<StudentBindVO> searchBindStudents(String keyword);
}
