package com.milk.order.module.user.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 用户保存请求 DTO（新增）
 */
@Data
public class UserSaveRequest implements Serializable {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    private String phone;

    private String email;

    /** 角色编码（ADMIN/TEACHER/PARENT） */
    @NotBlank(message = "角色不能为空")
    private String roleCode;

    /** 账号状态：0-禁用，1-正常 */
    private Integer status = 1;

    /** 关联学生 ID（家长账号） */
    private Long studentId;

    /** 关联班级 ID（班主任账号） */
    private Long classId;

    private String remark;
}
