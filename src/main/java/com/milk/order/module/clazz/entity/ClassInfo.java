package com.milk.order.module.clazz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 班级表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_info")
public class ClassInfo extends BaseEntity {

    /** 班级名称（如：一年级1班） */
    private String className;

    /** 年级 ID */
    private Long gradeId;

    /** 班主任用户 ID */
    private Long teacherId;

    /** 班级人数 */
    private Integer studentCount;

    /** 备注 */
    private String remark;
}
