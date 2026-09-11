package com.milk.order.module.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.system.entity.OperationLog;
import com.milk.order.module.system.service.OperationLogService;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统管理控制器
 *
 * 接口清单：
 * - GET /api/system/role/list        角色列表
 * - GET /api/system/log/list         操作日志分页
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SysRoleService sysRoleService;
    private final OperationLogService operationLogService;

    @GetMapping("/role/list")
    public ApiResponse<List<SysRole>> roleList() {
        List<SysRole> roles = sysRoleService.lambdaQuery()
                .orderByAsc(SysRole::getSort)
                .list();
        return ApiResponse.success(roles);
    }

    @GetMapping("/log/list")
    public ApiResponse<PageResult<OperationLog>> logList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        IPage<OperationLog> page = operationLogService.pageLogs(pageNum, pageSize, username, status, startTime, endTime);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }
}
