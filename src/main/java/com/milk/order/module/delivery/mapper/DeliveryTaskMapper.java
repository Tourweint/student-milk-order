package com.milk.order.module.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.delivery.entity.DeliveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {

    /**
     * 某学生「剩余待配送」盒数（未完成 = 待配送 1 + 配送中 2）的库内求和。
     *
     * <p>家长端首页计数只回传一个数，不必把该学生命中索引的若干行取回 Java 再求和；
     * 走 `idx_student_status (student_id, status)`。`deleted = 0` 为逻辑删除条件
     * （原生 SQL 不走 MyBatis-Plus 的 @TableLogic 自动拼接，必须显式写）。</p>
     */
    @Select("SELECT IFNULL(SUM(quantity), 0) FROM delivery_task "
            + "WHERE student_id = #{studentId} AND status IN (1, 2) AND deleted = 0")
    Integer sumPendingQuantityByStudent(@Param("studentId") Long studentId);
}
