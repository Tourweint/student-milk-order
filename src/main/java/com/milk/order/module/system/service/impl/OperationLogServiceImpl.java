package com.milk.order.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.module.system.entity.OperationLog;
import com.milk.order.module.system.mapper.OperationLogMapper;
import com.milk.order.module.system.service.OperationLogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements OperationLogService {

    @Override
    public IPage<OperationLog> pageLogs(Long pageNum, Long pageSize, String username,
                                        Integer status, String startTime, String endTime) {
        Page<OperationLog> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(username), OperationLog::getUsername, username)
                .eq(status != null, OperationLog::getStatus, status)
                .ge(StringUtils.hasText(startTime), OperationLog::getCreateTime,
                        StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime.replace(" ", "T")) : null)
                .le(StringUtils.hasText(endTime), OperationLog::getCreateTime,
                        StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime.replace(" ", "T")) : null)
                .orderByDesc(OperationLog::getId);
        return page(page, wrapper);
    }
}
