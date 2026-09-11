package com.milk.order.module.user.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 用户更新请求 DTO
 */
@Data
public class UserUpdateRequest implements Serializable {

    @NotNull(message = "用户ID不能为空")
    private Long id;

    /** 真实姓名 */
    private String realName;

    private String phone;

    private String email;

    /** 角色编码（ADMIN/TEACHER/PARENT） */
    private String roleCode;

    /** 账号状态：0-禁用，1-正常 */
    private Integer status;

    /** 关联学生 ID（家长账号） */
    private Long studentId;

    /** 关联班级 ID（班主任账号） */
    private Long classId;

    private String remark;
}
