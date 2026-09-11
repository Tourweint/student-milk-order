package com.milk.order.module.clazz.vo;

import com.milk.order.module.clazz.entity.ClassInfo;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级视图对象（附带年级名称、班主任姓名）
 */
@Data
public class ClassVO implements Serializable {

    private Long id;

    private String className;

    private Long gradeId;

    /** 年级名称 */
    private String gradeName;

    private Long teacherId;

    /** 班主任姓名 */
    private String teacherName;

    private Integer studentCount;

    private String remark;

    private LocalDateTime createTime;

    public static ClassVO from(ClassInfo clazz, String gradeName, String teacherName) {
        ClassVO vo = new ClassVO();
        vo.setId(clazz.getId());
        vo.setClassName(clazz.getClassName());
        vo.setGradeId(clazz.getGradeId());
        vo.setGradeName(gradeName);
        vo.setTeacherId(clazz.getTeacherId());
        vo.setTeacherName(teacherName);
        vo.setStudentCount(clazz.getStudentCount());
        vo.setRemark(clazz.getRemark());
        vo.setCreateTime(clazz.getCreateTime());
        return vo;
    }
}
