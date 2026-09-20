package com.milk.order.module.delivery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.delivery.dto.DeliveryExceptionSaveRequest;
import com.milk.order.module.delivery.entity.DeliveryException;

import java.util.List;

/**
 * 配送例外服务：管理员维护「停送日 / 补课日」，供配送日历重排使用。
 * 系统不做官方节假日的自动推算，例外表是唯一权威。
 */
public interface DeliveryExceptionService extends IService<DeliveryException> {

    /** 按日期区间查询例外（startDate/endDate 可空，按日期升序） */
    List<DeliveryException> listRange(String startDate, String endDate);

    /**
     * 新增/修改例外。日期唯一（撞键报错），只允许今天及以后的日期（历史不可改事实）。
     *
     * @return 保存后的例外 ID
     */
    Long save(DeliveryExceptionSaveRequest request);

    /** 删除例外（逻辑删除） */
    void delete(Long id);
}
