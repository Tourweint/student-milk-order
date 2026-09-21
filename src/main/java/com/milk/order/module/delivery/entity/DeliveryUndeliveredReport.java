package com.milk.order.module.delivery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当日未送达申报（奶站申报：任务已标记"已送出"，但物理上没有送到）
 *
 * <p><b>要解决的问题</b>：自动签收兜底按「配送日期 &lt; 今天 + 任务配送中 + 记录未签收」判定"超时未签收"，
 * 等于把**信息态**（配送站点了"今日已送出"）当成了**物理态**（奶已到校）。奶站当天实际没送到时，
 * 兜底会补出一条**虚假签收**并生成营养摄入——这正是"物理/信息混同"造成的一类错误业务事实。
 * 本表把该任务**排除出自动签收候选集**。</p>
 *
 * <p><b>刻意的边界</b>：只做标记与待办，**不新增状态、不自动改任何状态**——任务保持「配送中」，
 * 由人工经既有出口处置（签收 / 拒收 / 取消），处置后任务自然离开「配送中」。
 * 因此本次改动不引入新的状态机与新的迁移规则，也不影响任何不变量判定口径。</p>
 *
 * <p>幂等：一条任务只允许一条申报（`uk_task` 唯一键仲裁，重复申报由 {@code IdempotencyGuard} 翻译为业务提示）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("delivery_undelivered_report")
public class DeliveryUndeliveredReport extends BaseEntity {

    /** 配送任务 ID */
    private Long taskId;

    /** 订单 ID（冗余，便于列表与统计） */
    private Long orderId;

    /** 奶品 ID（冗余） */
    private Long productId;

    /** 班级 ID（冗余，与 delivery_task 一致，用于班主任数据范围过滤） */
    private Long classId;

    /** 配送日期（冗余） */
    private LocalDate deliveryDate;

    /** 未送达原因 */
    private String reason;

    /** 申报人（用户名，审计痕迹） */
    private String reportBy;

    /** 跟进状态：0-待跟进，1-已跟进 */
    private Integer handleStatus;

    /** 跟进说明（人工处置结果） */
    private String handleRemark;

    /** 跟进人 */
    private String handleBy;

    /** 跟进时间 */
    private LocalDateTime handleTime;
}
