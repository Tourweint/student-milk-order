package com.milk.order.module.order.controller;

import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.pay.WechatPaySimulator;
import com.milk.order.module.order.service.OrderInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付回调通知接口（模拟）
 *
 * 对应真实链路中微信服务器主动 POST 商户通知地址推送支付结果。
 * 微信侧无 JWT 登录态，可信性由签名验证保证（SecurityConfig 中放行，此处强制验签）。
 * 应答约定与微信支付一致：处理成功返回 200 "SUCCESS"，失败返回 500 "FAIL"（微信会重试通知）。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay/wechat")
@RequiredArgsConstructor
public class WechatPayNotifyController {

    private final WechatPaySimulator wechatPaySimulator;
    private final OrderInfoService orderInfoService;

    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> payNotify(
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        // 1. 验签：签名不合法的回调直接拒绝
        if (!wechatPaySimulator.verifyNotifySignature(rawBody, signature)) {
            log.warn("[模拟微信支付] 回调验签失败，拒绝处理");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        // 2. 解析报文并幂等处理支付结果
        WechatPayNotifyRequest notify;
        try {
            notify = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawBody, WechatPayNotifyRequest.class);
        } catch (Exception e) {
            log.warn("[模拟微信支付] 回调报文解析失败：{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        boolean handled = orderInfoService.handleWechatPayNotify(notify);
        return handled
                ? ResponseEntity.ok("SUCCESS")
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
    }
}
