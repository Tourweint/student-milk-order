package com.milk.order.module.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterRequest implements Serializable {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    private String phone;

    /** 角色编码（PARENT/TEACHER），管理员由后台创建 */
    private String roleCode;

    /** 家长注册时关联的学生 ID */
    private Long studentId;

    /** 班主任注册时关联的班级 ID */
    private Long classId;
}
