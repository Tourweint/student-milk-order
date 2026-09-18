package com.milk.order.module.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.product.entity.DailyQuota;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface DailyQuotaMapper extends BaseMapper<DailyQuota> {

    /**
     * 悲观行锁读取某日某品种的配额池（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>用途：扣减必须「先确认该池剩余量、再写回已售数」，而读与写之间若不加锁，
     * 同一行会被其他事务修改。MySQL 允许 UPDATE 自动读到最新版本，但 MariaDB 在
     * REPEATABLE READ 下会直接以
     * “Record has changed since last read in table 'daily_quota'” 拒绝更新
     * ——这是实验一在并发扣减下暴露的真实缺陷。</p>
     *
     * <p>加锁后本事务读到的是最新已提交版本，且在提交前不会被并发插入，
     * 因而在 MySQL 与 MariaDB 上行为一致，也消除了“先读快照、再写最新”的语义撕裂。</p>
     */
    @Select("SELECT * FROM daily_quota WHERE quota_date = #{quotaDate} AND product_id = #{productId} "
            + "AND deleted = 0 FOR UPDATE")
    DailyQuota selectForUpdate(@Param("quotaDate") LocalDate quotaDate, @Param("productId") Long productId);
}
