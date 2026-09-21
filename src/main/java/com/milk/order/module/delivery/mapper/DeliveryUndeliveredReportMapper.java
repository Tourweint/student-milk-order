package com.milk.order.module.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.delivery.entity.DeliveryUndeliveredReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

public interface DeliveryUndeliveredReportMapper extends BaseMapper<DeliveryUndeliveredReport> {

    /**
     * 在给定任务范围内筛出「已被申报未送达」的任务 ID。
     *
     * <p>自动签收兜底用它把申报过的任务排除出候选集。刻意用「任务 ID 白名单 + IN」而不是
     * 子查询或全表捞取：调用方每轮候选最多 {@code MAX_AUTO_SIGN_BATCH} 条，查询规模有上界。</p>
     */
    @Select("<script>SELECT task_id FROM delivery_undelivered_report WHERE deleted = 0 AND task_id IN "
            + "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectReportedTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
