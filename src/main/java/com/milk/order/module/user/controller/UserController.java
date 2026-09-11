package com.milk.order.module.user.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.user.dto.UserResetPasswordRequest;
import com.milk.order.module.user.dto.UserSaveRequest;
import com.milk.order.module.user.dto.UserUpdateRequest;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.entity.SysUserRole;
import com.milk.order.module.user.service.SysRoleService;
import com.milk.order.module.user.service.SysUserRoleService;
import com.milk.order.module.user.service.SysUserService;
import com.milk.order.module.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理控制器
 *
 * 接口清单：
 * - GET    /api/user/list      分页查询用户列表
 * - GET    /api/user/{id}      获取用户详情
 * - GET    /api/user/roles     角色列表（下拉选择用）
 * - POST   /api/user           新增用户
 * - PUT    /api/user           修改用户
 * - DELETE /api/user/{id}      删除用户
 * - PUT    /api/user/{id}/password  重置密码
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService sysUserService;
    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/list")
    public ApiResponse<PageResult<UserVO>> list(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) String keyword) {

        IPage<SysUser> page = sysUserService.pageUsers(pageNum, pageSize, keyword);

        List<UserVO> voList = page.getRecords().stream()
                .map(user -> UserVO.from(user, sysUserRoleService.getRoleCodesByUserId(user.getId())))
                .collect(Collectors.toList());

        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), voList));
    }

    @GetMapping("/roles")
    public ApiResponse<List<SysRole>> roles() {
        return ApiResponse.success(sysRoleService.lambdaQuery()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getSort)
                .list());
    }

    @GetMapping("/{id}")
    public ApiResponse<UserVO> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return ApiResponse.success(UserVO.from(user, sysUserRoleService.getRoleCodesByUserId(id)));
    }

    @PostMapping
    public ApiResponse<Void> save(@Valid @RequestBody UserSaveRequest request) {
        // 校验用户名唯一
        if (sysUserService.isUsernameExists(request.getUsername(), null)) {
            throw new BusinessException("用户名已存在");
        }
        // 校验角色
        SysRole role = getRoleByCode(request.getRoleCode());
        if (role == null) {
            throw new BusinessException("无效的角色类型");
        }

        // 创建用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        user.setStudentId(request.getStudentId());
        user.setClassId(request.getClassId());
        user.setRemark(request.getRemark());
        sysUserService.save(user);

        // 分配角色
        saveUserRole(user.getId(), role.getId());

        return ApiResponse.success();
    }

    @PutMapping
    public ApiResponse<Void> update(@Valid @RequestBody UserUpdateRequest request) {
        SysUser user = sysUserService.getById(request.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 更新基本信息
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        user.setStudentId(request.getStudentId());
        user.setClassId(request.getClassId());
        if (request.getRemark() != null) {
            user.setRemark(request.getRemark());
        }
        sysUserService.updateById(user);

        // 更新角色
        if (StringUtils.hasText(request.getRoleCode())) {
            SysRole role = getRoleByCode(request.getRoleCode());
            if (role == null) {
                throw new BusinessException("无效的角色类型");
            }
            // 删除旧角色，分配新角色
            sysUserRoleService.lambdaUpdate()
                    .eq(SysUserRole::getUserId, user.getId())
                    .remove();
            saveUserRole(user.getId(), role.getId());
        }

        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 删除用户角色关联
        sysUserRoleService.lambdaUpdate()
                .eq(SysUserRole::getUserId, id)
                .remove();
        sysUserService.removeById(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody UserResetPasswordRequest request) {
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        sysUserService.updateById(user);
        return ApiResponse.success();
    }

    private SysRole getRoleByCode(String roleCode) {
        return sysRoleService.lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)
                .eq(SysRole::getStatus, 1)
                .one();
    }

    private void saveUserRole(Long userId, Long roleId) {
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        sysUserRoleService.save(userRole);
    }
}
