package com.milk.order.module.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.user.entity.SysUserRole;

import java.util.List;

public interface SysUserRoleService extends IService<SysUserRole> {

    /**
     * 查询用户的所有角色编码
     */
    List<String> getRoleCodesByUserId(Long userId);
}
