package com.milk.order.process.reconcile;

import lombok.Builder;
import lombok.Getter;

/**
 * 一次补偿探测的候选主体（父过程的一个实例）。
 */
@Getter
@Builder
public class ProcessCandidate {

    /** 父过程主键 */
    private final Long id;

    /** 业务单号（订单号等），用于日志与人工定位 */
    private final String bizNo;

    /** 父过程当前状态 */
    private final Integer status;

    /** 父过程所在表名，如 order_info */
    private final String entityType;
}
