package com.milk.order.module.order.pay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信支付模拟配置（仅用于本地模拟微信支付链路，非真实商户配置）
 */
@Data
@Component
@ConfigurationProperties(prefix = "wxpay.mock")
public class WechatPayMockProperties {

    /** 模拟商户 API 密钥，用于回调通知签名/验签（本地模拟用，非真实凭据） */
    private String apiKey = "mock-wxpay-api-key";

    /** 模拟微信回调商户后端的通知地址 */
    private String notifyUrl = "http://127.0.0.1:8090/api/pay/wechat/notify";

    /** 模拟回调延迟（毫秒），模拟微信异步通知的网络耗时 */
    private long notifyDelayMs = 500;

    /** 回调失败最大重试次数（不含首次；指数退避 base*2^n，对齐微信重试节奏） */
    private int notifyMaxRetries = 5;

    /** 回调重试基础退避时长（毫秒），实际退避 = base * 2^attempt */
    private long notifyRetryBaseMs = 1000;
}
