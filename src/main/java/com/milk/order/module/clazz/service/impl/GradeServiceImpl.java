package com.milk.order.module.clazz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Grade;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.GradeMapper;
import com.milk.order.module.clazz.service.GradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GradeServiceImpl extends ServiceImpl<GradeMapper, Grade> implements GradeService {

    /** 直接用 Mapper 做存在性计数，避免与 ClassInfoService 循环依赖 */
    private final ClassInfoMapper classInfoMapper;

    @Override
    public List<Grade> listOrdered() {
        return lambdaQuery()
                .orderByAsc(Grade::getSort)
                .orderByAsc(Grade::getId)
                .list();
    }

    @Override
    public void createGrade(Grade grade) {
        validateName(grade);
        boolean duplicated = lambdaQuery()
                .eq(Grade::getGradeName, grade.getGradeName())
                .count() > 0;
        if (duplicated) {
            throw new BusinessException("年级名称已存在");
        }
        if (grade.getSort() == null) {
            grade.setSort(0);
        }
        save(grade);
    }

    @Override
    public void updateGrade(Grade grade) {
        if (grade.getId() == null) {
            throw new BusinessException("年级ID不能为空");
        }
        Grade exists = getById(grade.getId());
        if (exists == null) {
            throw new BusinessException("年级不存在");
        }
        validateName(grade);
        boolean duplicated = lambdaQuery()
                .eq(Grade::getGradeName, grade.getGradeName())
                .ne(Grade::getId, grade.getId())
                .count() > 0;
        if (duplicated) {
            throw new BusinessException("年级名称已存在");
        }
        updateById(grade);
    }

    @Override
    public void removeGrade(Long id) {
        Long classCount = classInfoMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ClassInfo>()
                        .eq(ClassInfo::getGradeId, id));
        if (classCount != null && classCount > 0) {
            throw new BusinessException("该年级下仍有班级，无法删除");
        }
        removeById(id);
    }

    private void validateName(Grade grade) {
        if (!StringUtils.hasText(grade.getGradeName())) {
            throw new BusinessException("年级名称不能为空");
        }
    }
}
