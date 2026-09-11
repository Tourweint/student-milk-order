package com.milk.order.module.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.mapper.SysRoleMapper;
import com.milk.order.module.user.service.SysRoleService;
import org.springframework.stereotype.Service;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {
}
