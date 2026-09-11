package com.milk.order.module.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 数据权限范围：描述当前登录用户可访问的数据范围
 * <ul>
 *   <li>all=true：管理员，不限制</li>
 *   <li>classId 非空：班主任，仅本班</li>
 *   <li>studentId 非空：家长，仅自己绑定的学生</li>
 * </ul>
 */
@Data
public class DataScope implements Serializable {

    /** 是否不限范围（管理员） */
    private boolean all;

    /** 限定班级 ID（班主任） */
    private Long classId;

    /** 限定学生 ID（家长） */
    private Long studentId;

    /** 是否需要按数据范围过滤 */
    public boolean isScoped() {
        return !all;
    }
}
