package com.milk.order.module.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.system.dto.StateRuleUpdateRequest;
import com.milk.order.module.system.dto.SysConfigUpdateRequest;
import com.milk.order.module.system.entity.OperationLog;
import com.milk.order.module.system.entity.StateTransitionRule;
import com.milk.order.module.system.entity.SysConfig;
import com.milk.order.module.system.service.StateMachineService;
import com.milk.order.module.system.service.SysConfigService;
import com.milk.order.module.system.service.OperationLogService;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import java.util.List;

/**
 * 系统管理控制器
 *
 * 接口清单：
 * - GET /api/system/role/list        角色列表
 * - GET /api/system/log/list         操作日志分页
 * - GET /api/system/state-rule/list  状态迁移规则列表（?scene=ORDER/DELIVERY_TASK/SUBSCRIPTION_PLAN）
 * - PUT /api/system/state-rule       修改状态迁移规则（allowed 0/1，在线生效）
 * - GET /api/system/config/list      系统参数列表（支付超时/对账开关等）
 * - PUT /api/system/config           修改系统参数（在线生效）
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SysRoleService sysRoleService;
    private final OperationLogService operationLogService;
    private final StateMachineService stateMachineService;
    private final SysConfigService sysConfigService;

    @GetMapping("/role/list")
    public ApiResponse<List<SysRole>> roleList() {
        List<SysRole> roles = sysRoleService.lambdaQuery()
                .orderByAsc(SysRole::getSort)
                .list();
        return ApiResponse.success(roles);
    }

    // ==================== 状态迁移规则（管理端在线配置，无需改代码） ====================

    @GetMapping("/state-rule/list")
    public ApiResponse<List<StateTransitionRule>> stateRuleList(@RequestParam(required = false) String scene) {
        return ApiResponse.success(stateMachineService.listRules(scene));
    }

    @PutMapping("/state-rule")
    public ApiResponse<Void> updateStateRule(@Valid @RequestBody StateRuleUpdateRequest request) {
        stateMachineService.updateRule(request.getId(), request.getAllowed(), request.getDescription());
        return ApiResponse.success();
    }

    // ==================== 系统参数（管理端在线配置） ====================

    @GetMapping("/config/list")
    public ApiResponse<List<SysConfig>> configList() {
        return ApiResponse.success(sysConfigService.listAll());
    }

    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(@Valid @RequestBody SysConfigUpdateRequest request) {
        sysConfigService.updateValue(request.getId(), request.getConfigValue());
        return ApiResponse.success();
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
