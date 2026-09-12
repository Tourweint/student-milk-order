package com.milk.order.module.clazz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Grade;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.clazz.service.ClassInfoService;
import com.milk.order.module.clazz.service.GradeService;
import com.milk.order.module.clazz.vo.ClassVO;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.service.DataScopeResolver;
import com.milk.order.module.user.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassInfoServiceImpl extends ServiceImpl<ClassInfoMapper, ClassInfo> implements ClassInfoService {

    private final GradeService gradeService;
    private final SysUserService sysUserService;
    /** 删除班级前用它统计学生数，避免与 StudentService 循环依赖 */
    private final StudentMapper studentMapper;
    /** 班级列表/下拉数据权限：班主任仅本班，管理员不限 */
    private final DataScopeResolver dataScopeResolver;

    @Override
    public IPage<ClassVO> pageClasses(Long pageNum, Long pageSize, Long gradeId) {
        Page<ClassInfo> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        DataScope scope = dataScopeResolver.resolve();
        IPage<ClassInfo> classPage = lambdaQuery()
                .eq(gradeId != null, ClassInfo::getGradeId, gradeId)
                .eq(scope.getClassId() != null, ClassInfo::getId, scope.getClassId())
                .orderByAsc(ClassInfo::getGradeId)
                .orderByAsc(ClassInfo::getId)
                .page(page);

        List<ClassVO> voList = convertToVOList(classPage.getRecords());

        Page<ClassVO> result = new Page<>(classPage.getCurrent(), classPage.getSize(), classPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public List<ClassVO> listAll() {
        // 数据权限：班主任下拉仅返回本班，管理员返回全部
        DataScope scope = dataScopeResolver.resolve();
        List<ClassInfo> classes = lambdaQuery()
                .eq(scope.getClassId() != null, ClassInfo::getId, scope.getClassId())
                .orderByAsc(ClassInfo::getGradeId)
                .orderByAsc(ClassInfo::getId)
                .list();
        return convertToVOList(classes);
    }

    @Override
    public void createClass(ClassInfo classInfo) {
        validateAndFill(classInfo, null);
        if (classInfo.getStudentCount() == null) {
            classInfo.setStudentCount(0);
        }
        save(classInfo);
    }

    @Override
    public void updateClass(ClassInfo classInfo) {
        if (classInfo.getId() == null) {
            throw new BusinessException("班级ID不能为空");
        }
        ClassInfo exists = getById(classInfo.getId());
        if (exists == null) {
            throw new BusinessException("班级不存在");
        }
        validateAndFill(classInfo, classInfo.getId());
        updateById(classInfo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeClass(Long id) {
        ClassInfo exists = getById(id);
        if (exists == null) {
            throw new BusinessException("班级不存在");
        }
        Long studentCount = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getClassId, id));
        if (studentCount != null && studentCount > 0) {
            throw new BusinessException("该班级下仍有学生，无法删除");
        }
        removeById(id);
    }

    @Override
    public void refreshStudentCount(Long classId) {
        if (classId == null) {
            return;
        }
        ClassInfo clazz = getById(classId);
        if (clazz == null) {
            return;
        }
        Long count = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getClassId, classId));
        clazz.setStudentCount(count == null ? 0 : count.intValue());
        updateById(clazz);
    }

    /**
     * 校验班级名称、年级、班主任，并补全默认值
     */
    private void validateAndFill(ClassInfo classInfo, Long excludeId) {
        if (!StringUtils.hasText(classInfo.getClassName())) {
            throw new BusinessException("班级名称不能为空");
        }
        if (classInfo.getGradeId() == null) {
            throw new BusinessException("请选择所属年级");
        }
        Grade grade = gradeService.getById(classInfo.getGradeId());
        if (grade == null) {
            throw new BusinessException("所选年级不存在");
        }
        boolean duplicated = lambdaQuery()
                .eq(ClassInfo::getClassName, classInfo.getClassName())
                .ne(excludeId != null, ClassInfo::getId, excludeId)
                .count() > 0;
        if (duplicated) {
            throw new BusinessException("班级名称已存在");
        }
        if (classInfo.getTeacherId() != null) {
            SysUser teacher = sysUserService.getById(classInfo.getTeacherId());
            if (teacher == null) {
                throw new BusinessException("所选班主任不存在");
            }
        }
    }

    /**
     * 批量转换：一次查出关联的年级、班主任，避免 N+1 查询
     */
    private List<ClassVO> convertToVOList(List<ClassInfo> classes) {
        if (CollectionUtils.isEmpty(classes)) {
            return Collections.emptyList();
        }

        // 年级映射
        Set<Long> gradeIds = classes.stream()
                .map(ClassInfo::getGradeId)
                .collect(Collectors.toSet());
        Map<Long, Grade> gradeMap = gradeService.listByIds(gradeIds).stream()
                .collect(Collectors.toMap(Grade::getId, Function.identity()));

        // 班主任映射
        Set<Long> teacherIds = classes.stream()
                .map(ClassInfo::getTeacherId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SysUser> teacherMap = teacherIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserService.listByIds(teacherIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        return classes.stream().map(clazz -> {
            Grade grade = gradeMap.get(clazz.getGradeId());
            SysUser teacher = clazz.getTeacherId() == null ? null : teacherMap.get(clazz.getTeacherId());
            return ClassVO.from(clazz,
                    grade == null ? null : grade.getGradeName(),
                    teacher == null ? null : teacher.getRealName());
        }).collect(Collectors.toList());
    }
}
