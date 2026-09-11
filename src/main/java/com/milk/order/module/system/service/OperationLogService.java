package com.milk.order.module.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.system.entity.OperationLog;

public interface OperationLogService extends IService<OperationLog> {

    /** 操作日志分页（用户名/状态/时间范围筛选） */
    IPage<OperationLog> pageLogs(Long pageNum, Long pageSize, String username,
                                 Integer status, String startTime, String endTime);
}
