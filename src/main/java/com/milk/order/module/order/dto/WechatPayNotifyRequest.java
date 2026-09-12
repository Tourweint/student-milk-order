package com.milk.order.module.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟微信支付结果回调通知体（对应真实链路中微信服务器 POST 到商户通知地址的报文）
 */
@Data
public class WechatPayNotifyRequest {

    /** 商户订单号（本系统订单编号） */
    private String outTradeNo;

    /** 微信支付流水号（模拟生成） */
    private String transactionId;

    /** 支付金额（元） */
    private BigDecimal amount;

    /** 支付时间（yyyy-MM-dd HH:mm:ss） */
    private String payTime;

    /** 支付结果：SUCCESS-支付成功 */
    private String resultCode;
}
