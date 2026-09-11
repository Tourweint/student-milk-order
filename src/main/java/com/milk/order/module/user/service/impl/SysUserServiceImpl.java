package com.milk.order.module.user.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.entity.SysUserRole;
import com.milk.order.module.user.mapper.SysRoleMapper;
import com.milk.order.module.user.mapper.SysUserRoleMapper;
import com.milk.order.module.user.mapper.SysUserMapper;
import com.milk.order.module.user.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    @Override
    public SysUser getByUsername(String username) {
        return lambdaQuery()
                .eq(SysUser::getUsername, username)
                .one();
    }

    @Override
    public SysUser getByOpenid(String openid) {
        return lambdaQuery()
                .eq(SysUser::getOpenid, openid)
                .one();
    }

    @Override
    public boolean isUsernameExists(String username, Long excludeId) {
        return lambdaQuery()
                .eq(SysUser::getUsername, username)
                .ne(excludeId != null, SysUser::getId, excludeId)
                .count() > 0;
    }

    @Override
    public IPage<SysUser> pageUsers(Long pageNum, Long pageSize, String keyword) {
        Page<SysUser> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        return lambdaQuery()
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(SysUser::getUsername, keyword)
                        .or()
                        .like(SysUser::getRealName, keyword)
                        .or()
                        .like(SysUser::getPhone, keyword))
                .orderByDesc(SysUser::getCreateTime)
                .page(page);
    }

    @Override
    public List<SysUser> listByRoleCode(String roleCode) {
        SysRole role = sysRoleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, roleCode));
        if (role == null) {
            return Collections.emptyList();
        }
        List<Long> userIds = sysUserRoleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getRoleId, role.getId()))
                .stream()
                .map(SysUserRole::getUserId)
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .in(SysUser::getId, userIds)
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId)
                .list();
    }
}
