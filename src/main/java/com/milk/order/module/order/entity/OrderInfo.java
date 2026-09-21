package com.milk.order.module.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_info")
public class OrderInfo extends BaseEntity {

    /** 订单编号 */
    private String orderNo;

    /** 学生 ID */
    private Long studentId;

    /** 家长用户 ID（下单人） */
    private Long userId;

    /** 班级 ID */
    private Long classId;

    /** 套餐 ID */
    private Long packageId;

    /** 订单类型：1-按月订购，2-按学期订购 */
    private Integer orderType;

    /** 订单状态：1-待支付，2-已支付，3-配送中，4-已完成，5-已退订 */
    private Integer status;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 实付金额（元） */
    private BigDecimal payAmount;

    /** 优惠金额（元） */
    private BigDecimal discountAmount;

    /**
     * 合同总盒数：支付成功后展开配送任务时一次性快照（此后不变），作为退款金额的分母基准。
     *
     * <p>不能改用实时 {@code SUM(delivery_task.quantity)} 反推——平移/重排会 CAS 作废任务但保留行、
     * 拒收补送会加量或新建任务（免费盒，不额外收钱）、期末摊平会重写 quantity，
     * 三者都会让实时求和漂移，进而算错退款比例。</p>
     */
    private Integer contractTotalBoxes;

    /** 配送开始日期 */
    private LocalDate deliveryStartDate;

    /** 配送结束日期 */
    private LocalDate deliveryEndDate;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 支付方式：1-模拟支付 */
    private Integer payType;

    /** 支付流水号 */
    private String transactionId;

    /** 退订时间 */
    private LocalDateTime cancelTime;

    /** 退订原因 */
    private String cancelReason;

    /** 备注 */
    private String remark;
}
