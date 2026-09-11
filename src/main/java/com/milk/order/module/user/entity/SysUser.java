package com.milk.order.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 用户名（登录账号） */
    private String username;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 账号状态：0-禁用，1-正常 */
    private Integer status;

    /** 关联学生 ID（家长账号时关联） */
    private Long studentId;

    /** 关联班级 ID（班主任账号时关联） */
    private Long classId;

    /** 微信 openid（家长微信授权登录绑定） */
    private String openid;

    /** 备注 */
    private String remark;
}
