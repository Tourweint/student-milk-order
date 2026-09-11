package com.milk.order.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.common.enums.RoleType;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据权限解析器：根据当前登录用户角色返回可访问的数据范围
 * <ul>
 *   <li>ADMIN：不限</li>
 *   <li>TEACHER：仅本班（classId）</li>
 *   <li>PARENT：仅自己绑定的学生（studentId）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleService sysUserRoleService;

    /**
     * 解析数据范围；未登录时抛 401。
     * 用于必须登录的列表/汇总查询，强制按角色收窄数据范围。
     */
    public DataScope resolve() {
        String username = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(401, "未登录");
        }
        return resolveByUsername(username);
    }

    /**
     * 解析数据范围；未登录（如定时任务、内部调用）时返回不限范围，不抛异常。
     * 用于单条记录的操作校验，兼容无登录上下文的内部流程。
     */
    public DataScope resolveQuietly() {
        String username = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(username)) {
            DataScope scope = new DataScope();
            scope.setAll(true);
            return scope;
        }
        return resolveByUsername(username);
    }

    private DataScope resolveByUsername(String username) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());
        DataScope scope = new DataScope();
        if (roles.contains(RoleType.PARENT.getCode())) {
            scope.setStudentId(user.getStudentId());
        } else if (roles.contains(RoleType.TEACHER.getCode())) {
            scope.setClassId(user.getClassId());
        } else {
            scope.setAll(true);
        }
        return scope;
    }
}
