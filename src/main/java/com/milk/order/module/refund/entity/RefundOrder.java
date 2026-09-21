package com.milk.order.module.refund.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单（退款域父过程）。
 *
 * <p>一张退款单即一条资金记录，不另建退款明细表——部分退款是"多张单"，不是"一张单多次执行"；
 * 退款与配送任务的关联在执行时刻按 R1 口径实时查询（不落快照，避免申请与执行之间的窗口漂移）。</p>
 *
 * <p>状态迁移全部经 {@code ProcessTransitionExecutor}（场景 REFUND）：
 * 1 待审核 → 2 已审核待退款 → 3 已退款；1 待审核 → 4 已拒绝（可重新申请）。</p>
 *
 * <p>注：表上的生成列 {@code active_order_id}（配合唯一键 {@code uk_refund_active}）只用于约束
 * "同一订单最多一张进行中退款单"，由数据库维护，故实体中不映射该列。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("refund_order")
public class RefundOrder extends BaseEntity {

    /** 退款单号（RF+时间戳+实例标识+序列，跨实例唯一） */
    private String refundNo;

    /** 订单 ID */
    private Long orderId;

    /** 订单编号（冗余，便于按单号检索） */
    private String orderNo;

    /** 学生 ID */
    private Long studentId;

    /** 申请人用户 ID */
    private Long userId;

    /** 申请盒数（参考值；实际退款盒数以执行时刻按 R1 口径计算） */
    private Integer applyBoxCount;

    /** 累计已退盒数（该订单截至本单的累计值，执行时回填） */
    private Integer refundedBoxes;

    /** 退款金额（元，执行时回填） */
    private BigDecimal refundAmount;

    /** 状态：1-待审核，2-已审核待退款，3-已退款，4-已拒绝，5-已取消 */
    private Integer status;

    /** 申请原因 */
    private String applyReason;

    /** 审核人用户 ID */
    private Long auditUserId;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 审核意见 */
    private String auditRemark;

    /** 退款通道：1-模拟微信 */
    private Integer refundChannel;

    /** 退款完成时间 */
    private LocalDateTime refundTime;
}
