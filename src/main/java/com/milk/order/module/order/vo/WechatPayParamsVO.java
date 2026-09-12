package com.milk.order.module.order.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模拟微信支付的调起参数（对应真实链路中后端下发、wx.requestPayment 消费的支付凭证）
 */
@Data
public class WechatPayParamsVO implements Serializable {

    /** 小程序 AppID */
    private String appId;

    /** 时间戳（秒） */
    private String timeStamp;

    /** 随机串 */
    private String nonceStr;

    /** 预支付会话标识：prepay_id=xxx */
    @JsonProperty("package")
    private String packageValue;

    /** 签名类型 */
    private String signType;

    /** 支付签名（模拟） */
    private String paySign;

    /** 预支付单号（模拟微信侧唯一凭证，用户确认扣款时回传） */
    private String prepayId;

    /** 商户订单号（即本系统订单编号） */
    private String outTradeNo;

    /** 支付金额（元），用于前端展示 */
    private BigDecimal amount;
}
