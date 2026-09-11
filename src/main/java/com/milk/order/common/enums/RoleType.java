package com.milk.order.common.enums;

import lombok.Getter;

/**
 * 角色类型枚举
 */
@Getter
public enum RoleType {

    ADMIN("ADMIN", "系统管理员"),
    TEACHER("TEACHER", "班主任"),
    PARENT("PARENT", "学生/家长");

    private final String code;
    private final String desc;

    RoleType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
