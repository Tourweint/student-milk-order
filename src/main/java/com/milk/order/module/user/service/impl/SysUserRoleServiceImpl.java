package com.milk.order.module.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.entity.SysUserRole;
import com.milk.order.module.user.mapper.SysRoleMapper;
import com.milk.order.module.user.mapper.SysUserRoleMapper;
import com.milk.order.module.user.service.SysUserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {

    private final SysRoleMapper sysRoleMapper;

    @Override
    public List<String> getRoleCodesByUserId(Long userId) {
        List<Long> roleIds = lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .list()
                .stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toList());

        if (roleIds.isEmpty()) {
            return List.of();
        }

        return sysRoleMapper.selectBatchIds(roleIds)
                .stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
    }
}
