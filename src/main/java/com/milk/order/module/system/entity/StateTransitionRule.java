package com.milk.order.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 状态迁移规则表：某场景某动作从"来源状态"迁移是否允许，管理端可在线配置
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("state_transition_rule")
public class StateTransitionRule extends BaseEntity {

    /** 状态机场景：ORDER-订单，DELIVERY_TASK-配送任务 */
    private String scene;

    /** 动作编码（见 StateTransitions） */
    private String action;

    /** 来源状态码（对应各表 status 字段） */
    private Integer fromStatus;

    /** 是否允许迁移：1-允许，0-禁止 */
    private Integer allowed;

    /** 规则说明 */
    private String description;
}
