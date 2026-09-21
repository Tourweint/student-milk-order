package com.milk.order.module.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.delivery.entity.DeliveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

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

    /**
     * 某品种在基准日 D 之前（含 D）**尚未送出**（{@code status=1}）的盒数合计。
     *
     * <p>用途：仓库侧的"实物需求"口径（发行封顶 R5′ 与短交预警共用）。
     * **必须含逾期任务**（{@code delivery_date < D}）而不能只算当天：池口径含结转池
     * （D−2、D−1 的未售额度），若任务只算当天，"早先已售未送出"与结转池会对同一批实物
     * 重复计入可卖额度，实测可超发（详见设计方案 §11.2）。</p>
     *
     * <p>也不含 {@code status=2}：任务已送出即已记 OUT 出库、仓库余量已扣减，再计一次是重复。</p>
     */
    @Select("SELECT IFNULL(SUM(quantity), 0) FROM delivery_task "
            + "WHERE product_id = #{productId} AND status = 1 "
            + "AND delivery_date <= #{baseDate} AND deleted = 0")
    Integer sumPendingQuantityUpTo(@Param("productId") Long productId, @Param("baseDate") LocalDate baseDate);
}
