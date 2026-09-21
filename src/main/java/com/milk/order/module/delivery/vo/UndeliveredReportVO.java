package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当日未送达申报视图对象（管理端待跟进列表）
 *
 * <p>列表是"人工跟进待办"：任务本身仍停留在「配送中」，需要人去核实并走既有出口
 * （签收 / 拒收 / 取消）处置，或线下补送。</p>
 */
@Data
public class UndeliveredReportVO implements Serializable {

    private Long id;

    private Long taskId;

    /** 配送任务编号 */
    private String taskNo;

    private Long orderId;

    /** 订单号 */
    private String orderNo;

    private LocalDate deliveryDate;

    private String className;

    private String studentName;

    private String productName;

    /** 该任务的配送盒数 */
    private Integer quantity;

    /** 未送达原因 */
    private String reason;

    /** 申报人 */
    private String reportBy;

    /** 申报时间 */
    private LocalDateTime reportTime;

    /** 跟进状态：0-待跟进，1-已跟进 */
    private Integer handleStatus;

    private String handleRemark;

    private String handleBy;

    private LocalDateTime handleTime;

    /** 任务当前状态（列表里回显：人工处置后应从「配送中」离开，便于核对是否已闭环） */
    private Integer taskStatus;
}
