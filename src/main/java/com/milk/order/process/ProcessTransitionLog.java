package com.milk.order.process;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务过程迁移台账：记录每一次状态迁移（谁、何时、把哪个对象、从什么状态迁到什么状态、是否生效）。
 *
 * <p>它是过程层的可观测基础：既支撑“过程回放/问题回溯”，也是父子状态聚合对账的数据依据。</p>
 */
@Data
@TableName("process_transition_log")
public class ProcessTransitionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 状态机场景，如 ORDER / DELIVERY_TASK / DELIVERY_RECORD */
    private String scene;

    /** 动作编码，如 PAY / SIGN / RENEW */
    private String action;

    /** 迁移主体表名，如 order_info */
    private String entityType;

    /** 迁移主体主键 */
    private Long entityId;

    /** 业务单号，如订单号、任务号 */
    private String bizNo;

    /** 迁移前状态 */
    private Integer fromStatus;

    /** 迁移后状态 */
    private Integer toStatus;

    /** 结果：1-迁移已生效，0-CAS 冲突未生效 */
    private Integer result;

    /** 操作人；无登录上下文（定时任务/对账补偿）记为 system */
    private String operatorName;

    /** 备注 */
    private String remark;

    /** 记录时间 */
    private LocalDateTime createTime;
}
