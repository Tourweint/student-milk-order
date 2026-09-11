package com.milk.order.module.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.user.entity.SysUser;

import java.util.List;

public interface SysUserService extends IService<SysUser> {

    /**
     * 根据用户名查询用户
     */
    SysUser getByUsername(String username);

    /**
     * 根据微信 openid 查询用户（未绑定返回 null）
     */
    SysUser getByOpenid(String openid);

    /**
     * 判断用户名是否已存在
     */
    boolean isUsernameExists(String username, Long excludeId);

    /**
     * 分页查询用户列表（支持关键词模糊匹配用户名/姓名/手机号）
     */
    IPage<SysUser> pageUsers(Long pageNum, Long pageSize, String keyword);

    /**
     * 按角色编码查询正常状态的用户（如下拉选择班主任）
     */
    List<SysUser> listByRoleCode(String roleCode);
}
