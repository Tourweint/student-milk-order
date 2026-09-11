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

    /** 家长角色：绑定的学生 ID（非家长为 null） */
    private Long studentId;

    /** 家长角色：绑定的学生姓名（非家长为 null） */
    private String studentName;

    private List<String> roles;
}
