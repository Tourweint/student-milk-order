package com.milk.order.module.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.system.entity.SysConfig;

import java.util.List;

/**
 * 系统参数配置服务：业务侧统一从此读取可在线调整的参数（带短缓存）
 */
public interface SysConfigService extends IService<SysConfig> {

    /** 读取字符串配置，不存在或为空返回默认值 */
    String getString(String key, String defaultValue);

    /** 读取整型配置，非法值回落默认值 */
    int getInt(String key, int defaultValue);

    /** 读取布尔配置（true/false，大小写不敏感），非法值回落默认值 */
    boolean getBool(String key, boolean defaultValue);

    /** 全部配置（管理端展示） */
    List<SysConfig> listAll();

    /** 修改配置值（管理端），成功后立即刷新缓存 */
    void updateValue(Long id, String value);
}
