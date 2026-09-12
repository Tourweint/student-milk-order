package com.milk.order.module.order.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.order.pay.WechatPaySimulator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟微信支付侧接口（扮演微信客户端，接收用户在模拟支付弹窗中的确认扣款动作）
 *
 * 真实链路中该步骤发生在微信 App 内；本地模拟由小程序前端调此接口触发微信异步回调商户。
 * 仅限本地模拟环境使用，上线前应整体移除。
 */
@RestController
@RequestMapping("/api/mock/wechat")
@RequiredArgsConstructor
public class MockWechatPayController {

    private final WechatPaySimulator wechatPaySimulator;

    /** 模拟用户在微信支付弹窗中确认扣款：微信侧受理后异步回调商户通知地址 */
    @PostMapping("/pay-confirm")
    public ApiResponse<String> payConfirm(@RequestBody PayConfirmRequest request) {
        boolean accepted = wechatPaySimulator.confirmPay(request.getPrepayId());
        if (!accepted) {
            return ApiResponse.error(400, "预支付单不存在或已处理，请重新发起支付");
        }
        return ApiResponse.success("accepted");
    }

    @Data
    public static class PayConfirmRequest {
        /** 预支付单号（预下单接口返回） */
        private String prepayId;
    }
}
