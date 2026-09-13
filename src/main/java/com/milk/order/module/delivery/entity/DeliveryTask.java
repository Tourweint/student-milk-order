package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配送任务表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_task")
public class DeliveryTask extends BaseEntity {

    /** 任务编号 */
    private String taskNo;

    /** 配送日期 */
    private LocalDate deliveryDate;

    /** 班级 ID */
    private Long classId;

    /** 订单 ID */
    private Long orderId;

    /** 学生 ID */
    private Long studentId;

    /** 奶品 ID */
    private Long productId;

    /** 配送数量 */
    private Integer quantity;

    /** 任务状态：1-待配送，2-配送中，3-已完成，4-已取消 */
    private Integer status;

    /** 配送人 ID */
    private Long deliveryPersonId;

    /** 派送操作人（用户名，开始配送时记录） */
    private String dispatchBy;

    /** 派送时间（开始配送时记录） */
    private LocalDateTime dispatchTime;

    /** 备注 */
    private String remark;
}
