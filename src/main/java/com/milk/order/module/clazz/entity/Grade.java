package com.milk.order.module.clazz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 年级表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("grade")
public class Grade extends BaseEntity {

    /** 年级名称（如：一年级、二年级） */
    private String gradeName;

    /** 年级编码（如：G1、G2） */
    private String gradeCode;

    /** 排序 */
    private Integer sort;

    /** 备注 */
    private String remark;
}
