package com.milk.order.module.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.system.entity.StateTransitionRule;

import java.util.List;

/**
 * 状态机规则服务：状态迁移是否允许由 state_transition_rule 表驱动
 * （内存缓存 + 60 秒过期兜底，管理端修改后立即生效）
 */
public interface StateMachineService extends IService<StateTransitionRule> {

    /**
     * 查询某场景某动作从 fromStatus 迁移是否允许；
     * 未配置的组合默认禁止（白名单语义）
     */
    boolean allowed(String scene, String action, Integer fromStatus);

    /**
     * 断言迁移允许，不允许时抛出带场景说明的业务异常
     *
     * @param sceneText 场景中文名（用于错误消息），如"订单"、"配送任务"
     */
    void assertAllowed(String scene, String action, Integer fromStatus, String sceneText);

    /** 按场景查询规则列表（scene 为空返回全部），供管理端配置 */
    List<StateTransitionRule> listRules(String scene);

    /** 修改规则（allowed/说明），成功后立即刷新缓存 */
    void updateRule(Long id, Integer allowed, String description);
}
