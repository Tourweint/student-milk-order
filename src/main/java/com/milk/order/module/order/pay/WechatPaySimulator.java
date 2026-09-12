package com.milk.order.module.order.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.vo.WechatPayParamsVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 微信支付平台模拟器：扮演「微信支付」这一外部角色
 *
 * 模拟链路：
 * 1. unifiedOrder：商户（本系统）调统一下单，微信侧签发预支付凭证 prepayId 与调起签名参数
 * 2. confirmPay：用户在微信侧确认扣款（由前端调模拟接口触发）
 * 3. confirmPay 后异步 POST 签名回调报文到商户通知地址，由 WechatPayNotifyController 验签处理
 *
 * 仅限本地/演示环境使用；预支付单保存在内存中，重启后未完成的预支付单失效（重新发起支付即可）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPaySimulator {

    private final WechatPayMockProperties properties;

    /** 已签发的预支付单：prepayId -> 预支付信息（模拟微信侧的预支付单存储） */
    private final Map<String, PendingPrepay> prepayStore = new ConcurrentHashMap<>();

    /** 模拟微信异步通知的调度线程 */
    private final ScheduledExecutorService notifyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wxpay-mock-notify");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 预支付单（模拟微信侧） */
    @Data
    public static class PendingPrepay {
        private String outTradeNo;
        private BigDecimal amount;
        private Long orderId;
    }

    /**
     * 模拟微信统一下单：校验金额，签发 prepayId 并生成前端调起支付所需的参数
     */
    public WechatPayParamsVO unifiedOrder(OrderInfo order) {
        if (order.getPayAmount() == null
                || order.getPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单支付金额非法，无法发起支付");
        }
        String prepayId = "wx" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(100000, 999999);

        PendingPrepay prepay = new PendingPrepay();
        prepay.setOutTradeNo(order.getOrderNo());
        prepay.setAmount(order.getPayAmount());
        prepay.setOrderId(order.getId());
        prepayStore.put(prepayId, prepay);

        WechatPayParamsVO vo = new WechatPayParamsVO();
        vo.setAppId("wx-mock-appid");
        vo.setTimeStamp(String.valueOf(System.currentTimeMillis() / 1000));
        vo.setNonceStr(randomNonce());
        vo.setPackageValue("prepay_id=" + prepayId);
        vo.setSignType("HMAC-SHA256");
        vo.setPrepayId(prepayId);
        vo.setOutTradeNo(order.getOrderNo());
        vo.setAmount(order.getPayAmount());
        // 模拟签名：HMAC(apiKey, appId\n timeStamp\n nonceStr\n package)
        vo.setPaySign(hmacSha256(String.join("\n",
                vo.getAppId(), vo.getTimeStamp(), vo.getNonceStr(), vo.getPackageValue())));
        return vo;
    }

    /**
     * 模拟用户在微信侧确认扣款：成功后微信异步回调商户通知地址
     *
     * @return false 表示预支付单不存在或已消费（过期/重复确认）
     */
    public boolean confirmPay(String prepayId) {
        PendingPrepay prepay = prepayStore.remove(prepayId);
        if (prepay == null) {
            return false;
        }
        // 模拟微信收到扣款成功后，异步推送支付结果到商户通知地址
        notifyExecutor.schedule(() -> sendNotify(prepay),
                properties.getNotifyDelayMs(), TimeUnit.MILLISECONDS);
        return true;
    }

    /** 发送签名回调报文到商户通知地址 */
    private void sendNotify(PendingPrepay prepay) {
        try {
            WechatPayNotifyRequest body = new WechatPayNotifyRequest();
            body.setOutTradeNo(prepay.getOutTradeNo());
            body.setTransactionId("TX" + System.currentTimeMillis()
                    + ThreadLocalRandom.current().nextInt(1000, 9999));
            body.setAmount(prepay.getAmount());
            body.setPayTime(LocalDateTime.now().format(TIME_FMT));
            body.setResultCode("SUCCESS");

            String json = objectMapper.writeValueAsString(body);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 模拟微信支付签名机制：商户端需验签后才处理
            headers.set("Wechatpay-Signature", hmacSha256(json));
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    properties.getNotifyUrl(), new HttpEntity<>(json, headers), String.class);
            log.info("[模拟微信支付] 回调商户通知地址完成，outTradeNo={}，商户响应={}",
                    prepay.getOutTradeNo(), resp.getBody());
        } catch (Exception e) {
            // 通知失败：订单保持待支付，用户可重新发起支付（与真实链路一致，微信会重试通知）
            log.error("[模拟微信支付] 回调商户通知地址失败，outTradeNo={}，原因：{}",
                    prepay.getOutTradeNo(), e.getMessage());
        }
    }

    /** 验证回调报文签名（商户端调用） */
    public boolean verifyNotifySignature(String body, String signature) {
        if (body == null || signature == null || signature.isEmpty()) {
            return false;
        }
        return hmacSha256(body).equals(signature);
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getApiKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private String randomNonce() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }
}
