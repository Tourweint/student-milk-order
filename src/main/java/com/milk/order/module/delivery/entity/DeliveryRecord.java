package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 配送记录表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_record")
public class DeliveryRecord extends BaseEntity {

    /** 配送任务 ID */
    private Long taskId;

    /** 学生 ID */
    private Long studentId;

    /** 奶品 ID */
    private Long productId;

    /** 配送数量 */
    private Integer quantity;

    /** 签收状态：1-已签收，2-未签收，3-拒收 */
    private Integer signStatus;

    /** 签收时间 */
    private LocalDateTime signTime;

    /** 签收人（学生姓名或班主任） */
    private String signPerson;

    /** 备注 */
    private String remark;

    /** 拒收原因分类：DAMAGED/SOUR/WRONG_PRODUCT/SHORTAGE/OTHER（仅真拒收写入，退订/缺货取消不写） */
    private String rejectReasonCode;

    /** 拒收详细描述（班主任填写，可选） */
    private String rejectReasonDetail;
}
