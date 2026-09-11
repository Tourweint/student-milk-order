package com.milk.order.module.clazz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.vo.ClassVO;

import java.util.List;

public interface ClassInfoService extends IService<ClassInfo> {

    /**
     * 分页查询班级（可按年级筛选），附带年级名称与班主任姓名
     */
    IPage<ClassVO> pageClasses(Long pageNum, Long pageSize, Long gradeId);

    /**
     * 查询全部班级（下拉选择用），附带年级名称
     */
    List<ClassVO> listAll();

    /**
     * 新增班级（校验年级存在、班级名称不重复）
     */
    void createClass(ClassInfo classInfo);

    /**
     * 修改班级
     */
    void updateClass(ClassInfo classInfo);

    /**
     * 删除班级（其下存在学生时禁止删除）
     */
    void removeClass(Long id);

    /**
     * 重新统计并刷新某班级的学生人数
     */
    void refreshStudentCount(Long classId);
}
