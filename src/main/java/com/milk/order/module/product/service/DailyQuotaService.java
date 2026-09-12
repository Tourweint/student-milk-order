package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.entity.DailyQuota;

import java.time.LocalDate;
import java.util.List;

public interface DailyQuotaService extends IService<DailyQuota> {

    /** 按日期区间查询配额（升序） */
    List<DailyQuota> listRange(LocalDate startDate, LocalDate endDate);

    /** 设置某日机动配额（存在则修改；已售数不得超过新总额） */
    void setQuota(LocalDate quotaDate, Integer totalQuota, String remark);

    /** 某日剩余机动盒数（含保质期内前几日结转；窗口外自动作废） */
    int remaining(LocalDate quotaDate);

    /** 扣减某订单的机动配额（先卖最老池子，写台账幂等；余量不足时抛业务异常） */
    void deduct(Long orderId, LocalDate deliveryDate, int boxes);

    /** 按台账回补某订单占用的配额（退订时使用） */
    void restore(Long orderId);
}
