package com.milk.order.module.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.delivery.entity.DeliveryParentExemption;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DeliveryParentExemptionMapper extends BaseMapper<DeliveryParentExemption> {

    /**
     * 悲观行锁读取某学生某月的豁免计数器（`SELECT ... FOR UPDATE`）。
     *
     * <p>与配额池同一套并发语义：本方法是"读计数 → 判断 → 写回"，不加锁的话
     * 并发豁免会越过上限（读到的都是旧值）。</p>
     */
    @Select("SELECT * FROM delivery_parent_exemption WHERE student_id = #{studentId} "
            + "AND exempt_month = #{exemptMonth} AND deleted = 0 FOR UPDATE")
    DeliveryParentExemption selectForUpdate(@Param("studentId") Long studentId,
                                            @Param("exemptMonth") String exemptMonth);
}
