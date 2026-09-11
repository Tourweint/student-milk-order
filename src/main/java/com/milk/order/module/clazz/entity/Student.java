package com.milk.order.module.clazz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学生表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student")
public class Student extends BaseEntity {

    /** 学号 */
    private String studentNo;

    /** 学生姓名 */
    private String studentName;

    /** 性别：0-女，1-男 */
    private Integer gender;

    /** 班级 ID */
    private Long classId;

    /** 家长用户 ID */
    private Long parentId;

    /** 家长姓名 */
    private String parentName;

    /** 家长电话 */
    private String parentPhone;

    /** 出生日期 */
    private java.time.LocalDate birthDate;

    /** 备注 */
    private String remark;
}
