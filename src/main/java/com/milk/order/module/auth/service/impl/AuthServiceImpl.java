package com.milk.order.module.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.common.enums.RoleType;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.service.AuthService;
import com.milk.order.module.auth.vo.LoginResponse;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.entity.SysUserRole;
import com.milk.order.module.user.service.SysRoleService;
import com.milk.order.module.user.service.SysUserRoleService;
import com.milk.order.module.user.service.SysUserService;
import com.milk.order.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService sysUserService;
    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public LoginResponse login(LoginRequest request) {
        // 1. 查询用户
        SysUser user = sysUserService.getByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 2. 检查账号状态
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 3. 查询角色
        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());

        // 4. 生成 Token
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), roles);

        // 5. 组装返回
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRealName(user.getRealName());
        response.setRoles(roles);

        log.info("用户 [{}] 登录成功", user.getUsername());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        // 1. 校验用户名唯一
        if (sysUserService.isUsernameExists(request.getUsername(), null)) {
            throw new BusinessException("用户名已存在");
        }

        // 2. 校验角色
        String roleCode = request.getRoleCode() != null ? request.getRoleCode() : RoleType.PARENT.getCode();
        SysRole role = getRoleByCode(roleCode);
        if (role == null) {
            throw new BusinessException("无效的角色类型");
        }

        // 3. 创建用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setPhone(request.getPhone());
        user.setStatus(1);
        if (RoleType.PARENT.getCode().equals(roleCode)) {
            user.setStudentId(request.getStudentId());
        } else if (RoleType.TEACHER.getCode().equals(roleCode)) {
            user.setClassId(request.getClassId());
        }
        sysUserService.save(user);

        // 4. 分配角色
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        sysUserRoleService.save(userRole);

        log.info("用户 [{}] 注册成功，角色 [{}]", user.getUsername(), roleCode);
    }

    @Override
    public LoginResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new BusinessException(401, "未登录或登录已过期");
        }

        String username = authentication.getName();
        SysUser user = sysUserService.getByUsername(username);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }

        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());

        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRealName(user.getRealName());
        response.setRoles(roles);
        return response;
    }

    private SysRole getRoleByCode(String roleCode) {
        return sysRoleService.getOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode)
                .eq(SysRole::getStatus, 1));
    }
}
