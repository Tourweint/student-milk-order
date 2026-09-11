package com.milk.order.module.clazz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.clazz.entity.Grade;

import java.util.List;

public interface GradeService extends IService<Grade> {

    /**
     * 查询全部年级（按 sort 升序）
     */
    List<Grade> listOrdered();

    /**
     * 新增年级（校验名称非空且不重复）
     */
    void createGrade(Grade grade);

    /**
     * 修改年级
     */
    void updateGrade(Grade grade);

    /**
     * 删除年级（其下存在班级时禁止删除）
     */
    void removeGrade(Long id);
}
