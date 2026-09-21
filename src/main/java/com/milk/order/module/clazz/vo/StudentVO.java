package com.milk.order.module.clazz.vo;

import com.milk.order.module.clazz.entity.Student;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学生视图对象（附带班级名称）
 */
@Data
public class StudentVO implements Serializable {

    private Long id;

    /** 学号 */
    private String studentNo;

    /** 学生姓名 */
    private String studentName;

    /** 性别：0-女，1-男 */
    private Integer gender;

    private Long classId;

    /** 班级名称 */
    private String className;

    private Long parentId;

    private String parentName;

    private String parentPhone;

    private LocalDate birthDate;

    /** 过敏/禁忌标签（受控编码的逗号分隔串，前端按 /api/order/allergy-options 映射文案） */
    private String allergyTags;

    private String remark;

    private LocalDateTime createTime;

    public static StudentVO from(Student student, String className) {
        StudentVO vo = new StudentVO();
        vo.setId(student.getId());
        vo.setStudentNo(student.getStudentNo());
        vo.setStudentName(student.getStudentName());
        vo.setGender(student.getGender());
        vo.setClassId(student.getClassId());
        vo.setClassName(className);
        vo.setParentId(student.getParentId());
        vo.setParentName(student.getParentName());
        vo.setParentPhone(student.getParentPhone());
        vo.setBirthDate(student.getBirthDate());
        vo.setAllergyTags(student.getAllergyTags());
        vo.setRemark(student.getRemark());
        vo.setCreateTime(student.getCreateTime());
        return vo;
    }
}
