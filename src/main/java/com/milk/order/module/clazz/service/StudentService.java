package com.milk.order.module.clazz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.vo.ImportResultVO;
import com.milk.order.module.clazz.vo.StudentVO;
import org.springframework.web.multipart.MultipartFile;

public interface StudentService extends IService<Student> {

    /**
     * 分页查询学生（可按班级、关键词筛选），附带班级名称
     * <p>数据权限：家长仅看自己绑定的学生，班主任仅看本班，管理员不限</p>
     *
     * @param keyword 匹配学号 / 学生姓名 / 家长姓名 / 家长电话
     */
    IPage<StudentVO> pageStudents(Long pageNum, Long pageSize, Long classId, String keyword);

    /**
     * 学生详情（按数据权限校验访问范围：家长仅自己绑定的学生，班主任仅本班）
     */
    Student getStudentDetail(Long id);

    /**
     * Excel 批量导入学生
     * <p>数据权限：家长不可导入，班主任仅能导入本班</p>
     *
     * @param file    Excel 文件（列：学号、姓名、性别、家长姓名、家长电话、备注）
     * @param classId 默认班级 ID（必填，学生归入该班级）
     */
    ImportResultVO importStudents(MultipartFile file, Long classId);

    /**
     * 生成学生导入模板（.xlsx 字节）
     */
    byte[] buildImportTemplate() throws java.io.IOException;

    /**
     * 判断学号是否已存在
     */
    boolean isStudentNoExists(String studentNo, Long excludeId);

    /**
     * 新增学生（校验班级存在、学号唯一，并刷新班级人数）
     */
    void createStudent(Student student);

    /**
     * 修改学生（校验学号唯一，班级变更时同步两边人数）
     */
    void updateStudent(Student student);

    /**
     * 删除学生（并刷新班级人数）
     */
    void removeStudent(Long id);
}
