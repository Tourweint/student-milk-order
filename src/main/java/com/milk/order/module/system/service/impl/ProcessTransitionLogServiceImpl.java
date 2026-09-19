package com.milk.order.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.module.system.service.ProcessTransitionLogService;
import com.milk.order.process.ProcessTransitionLog;
import com.milk.order.process.mapper.ProcessTransitionLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class ProcessTransitionLogServiceImpl
        extends ServiceImpl<ProcessTransitionLogMapper, ProcessTransitionLog>
        implements ProcessTransitionLogService {

    @Override
    public IPage<ProcessTransitionLog> pageLogs(Long pageNum, Long pageSize, String scene, String action,
                                                String entityType, String bizNo, Integer result,
                                                String startTime, String endTime) {
        Page<ProcessTransitionLog> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<ProcessTransitionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(scene), ProcessTransitionLog::getScene, scene)
                .eq(StringUtils.hasText(action), ProcessTransitionLog::getAction, action)
                .eq(StringUtils.hasText(entityType), ProcessTransitionLog::getEntityType, entityType)
                .like(StringUtils.hasText(bizNo), ProcessTransitionLog::getBizNo, bizNo)
                .eq(result != null, ProcessTransitionLog::getResult, result)
                .ge(StringUtils.hasText(startTime), ProcessTransitionLog::getCreateTime,
                        StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime.replace(" ", "T")) : null)
                .le(StringUtils.hasText(endTime), ProcessTransitionLog::getCreateTime,
                        StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime.replace(" ", "T")) : null)
                .orderByDesc(ProcessTransitionLog::getId);
        return page(page, wrapper);
    }
}
