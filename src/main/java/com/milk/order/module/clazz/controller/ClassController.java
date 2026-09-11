package com.milk.order.module.clazz.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Grade;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.service.ClassInfoService;
import com.milk.order.module.clazz.service.GradeService;
import com.milk.order.module.clazz.service.StudentService;
import com.milk.order.module.clazz.vo.ClassVO;
import com.milk.order.module.clazz.vo.ImportResultVO;
import com.milk.order.module.clazz.vo.StudentVO;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 班级与学生管理控制器
 *
 * 接口清单：
 * 年级：
 * - GET    /api/clazz/grade/list       年级列表
 * - POST   /api/clazz/grade             新增年级
 * - PUT    /api/clazz/grade             修改年级
 * - DELETE /api/clazz/grade/{id}        删除年级
 * 班级：
 * - GET    /api/clazz/class/list        班级分页（可按年级筛选）
 * - GET    /api/clazz/class/all         全部班级（下拉用）
 * - GET    /api/clazz/class/teachers    班主任下拉（TEACHER 角色用户）
 * - GET    /api/clazz/class/{id}        班级详情
 * - POST   /api/clazz/class             新增班级
 * - PUT    /api/clazz/class             修改班级
 * - DELETE /api/clazz/class/{id}        删除班级
 * 学生：
 * - GET    /api/clazz/student/list      学生分页（可按班级、关键词筛选）
 * - GET    /api/clazz/student/{id}      学生详情
 * - POST   /api/clazz/student           新增学生
 * - PUT    /api/clazz/student           修改学生
 * - DELETE /api/clazz/student/{id}      删除学生
 * - POST   /api/clazz/student/import    Excel 批量导入学生
 */
@RestController
@RequestMapping("/api/clazz")
@RequiredArgsConstructor
public class ClassController {

    private final GradeService gradeService;
    private final ClassInfoService classInfoService;
    private final StudentService studentService;
    private final SysUserService sysUserService;

    // ==================== 年级 ====================

    @GetMapping("/grade/list")
    public ApiResponse<List<Grade>> gradeList() {
        return ApiResponse.success(gradeService.listOrdered());
    }

    @PostMapping("/grade")
    public ApiResponse<Void> saveGrade(@RequestBody Grade grade) {
        gradeService.createGrade(grade);
        return ApiResponse.success();
    }

    @PutMapping("/grade")
    public ApiResponse<Void> updateGrade(@RequestBody Grade grade) {
        gradeService.updateGrade(grade);
        return ApiResponse.success();
    }

    @DeleteMapping("/grade/{id}")
    public ApiResponse<Void> deleteGrade(@PathVariable Long id) {
        gradeService.removeGrade(id);
        return ApiResponse.success();
    }

    // ==================== 班级 ====================

    @GetMapping("/class/list")
    public ApiResponse<PageResult<ClassVO>> classList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long gradeId) {
        IPage<ClassVO> page = classInfoService.pageClasses(pageNum, pageSize, gradeId);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/class/all")
    public ApiResponse<List<ClassVO>> classAll() {
        return ApiResponse.success(classInfoService.listAll());
    }

    @GetMapping("/class/teachers")
    public ApiResponse<List<Map<String, Object>>> teacherOptions() {
        List<SysUser> teachers = sysUserService.listByRoleCode("TEACHER");
        List<Map<String, Object>> result = teachers.stream()
                .map(t -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", t.getId());
                    map.put("realName", t.getRealName());
                    return map;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @GetMapping("/class/{id}")
    public ApiResponse<ClassInfo> getClassById(@PathVariable Long id) {
        return ApiResponse.success(classInfoService.getById(id));
    }

    @PostMapping("/class")
    public ApiResponse<Void> saveClass(@RequestBody ClassInfo classInfo) {
        classInfoService.createClass(classInfo);
        return ApiResponse.success();
    }

    @PutMapping("/class")
    public ApiResponse<Void> updateClass(@RequestBody ClassInfo classInfo) {
        classInfoService.updateClass(classInfo);
        return ApiResponse.success();
    }

    @DeleteMapping("/class/{id}")
    public ApiResponse<Void> deleteClass(@PathVariable Long id) {
        classInfoService.removeClass(id);
        return ApiResponse.success();
    }

    // ==================== 学生 ====================

    @GetMapping("/student/list")
    public ApiResponse<PageResult<StudentVO>> studentList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String keyword) {
        IPage<StudentVO> page = studentService.pageStudents(pageNum, pageSize, classId, keyword);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/student/{id}")
    public ApiResponse<Student> getStudentById(@PathVariable Long id) {
        return ApiResponse.success(studentService.getById(id));
    }

    @PostMapping("/student")
    public ApiResponse<Void> saveStudent(@RequestBody Student student) {
        studentService.createStudent(student);
        return ApiResponse.success();
    }

    @PutMapping("/student")
    public ApiResponse<Void> updateStudent(@RequestBody Student student) {
        studentService.updateStudent(student);
        return ApiResponse.success();
    }

    @DeleteMapping("/student/{id}")
    public ApiResponse<Void> deleteStudent(@PathVariable Long id) {
        studentService.removeStudent(id);
        return ApiResponse.success();
    }

    @PostMapping("/student/import")
    public ApiResponse<ImportResultVO> importStudents(
            @RequestParam("file") MultipartFile file,
            @RequestParam("classId") Long classId) {
        return ApiResponse.success(studentService.importStudents(file, classId));
    }

    /**
     * 下载学生导入模板
     */
    @GetMapping("/student/template")
    public void downloadTemplate(javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        byte[] bytes = studentService.buildImportTemplate();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = java.net.URLEncoder.encode("学生导入模板.xlsx", java.nio.charset.StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
        try (java.io.OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }
}
