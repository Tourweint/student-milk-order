package com.milk.order.module.auth.vo;

import com.milk.order.module.clazz.vo.StudentVO;
import lombok.Data;

import java.io.Serializable;

/**
 * 微信绑定流程学生搜索结果（最小信息，免认证接口专用）
 */
@Data
public class StudentBindVO implements Serializable {

    private Long id;

    /** 学号 */
    private String studentNo;

    /** 学生姓名 */
    private String studentName;

    /** 班级名称 */
    private String className;

    /** 系统登记的家长姓名（供绑定时核对，可能为空） */
    private String parentName;

    public static StudentBindVO from(StudentVO vo) {
        StudentBindVO bind = new StudentBindVO();
        bind.setId(vo.getId());
        bind.setStudentNo(vo.getStudentNo());
        bind.setStudentName(vo.getStudentName());
        bind.setClassName(vo.getClassName());
        bind.setParentName(vo.getParentName());
        return bind;
    }
}
