package com.milk.order.module.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟微信侧支付单（扮演「微信支付平台」这一外部角色的存储）。
 *
 * <p>它取代了原先模拟器里的两个内存 Map。原因不是"内存不好"，而是**多实例部署下会出错**：
 * 预支付单只存在于签发它的那个实例，确认扣款或查单落到别的实例就会失败——
 * 前者让用户付不了款，后者会让对账任务把已扣款订单误判成未付款
 * （进而被超时任务取消，这是最坏的结果）。</p>
 *
 * <p>状态机：{@code 1 已签发预支付 → 2 已扣款}；同一订单重新发起支付时，旧的待确认预支付单置为
 * {@code 3 已作废}。扣款由「status=1 → 2」的条件更新完成，因此重复确认、并发确认只有一个成功。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wechat_pay_order")
public class WechatPayOrder extends BaseEntity {

    /** 1-已签发预支付（待用户确认扣款） */
    public static final int STATUS_PREPAID = 1;
    /** 2-已扣款 */
    public static final int STATUS_PAID = 2;
    /** 3-已作废（被同订单的新预支付单替换） */
    public static final int STATUS_VOIDED = 3;

    /** 预支付凭证（模拟微信签发） */
    private String prepayId;

    /** 商户订单号 */
    private String outTradeNo;

    /** 商户订单ID（便于排查） */
    private Long orderId;

    /** 金额（元） */
    private BigDecimal amount;

    /** 状态，见本类常量 */
    private Integer status;

    /** 模拟微信支付流水号（扣款后生成） */
    private String transactionId;

    /** 扣款时间 */
    private LocalDateTime payTime;
}
