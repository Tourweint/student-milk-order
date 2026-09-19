package com.milk.order.module.order.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.WechatPayOrder;
import com.milk.order.module.order.mapper.WechatPayOrderMapper;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 微信支付平台模拟器：扮演「微信支付」这一外部角色
 *
 * 模拟链路：
 * 1. unifiedOrder：商户（本系统）调统一下单，微信侧签发预支付凭证 prepayId 与调起签名参数
 * 2. confirmPay：用户在微信侧确认扣款（由前端调模拟接口触发）；扣款结果记入支付单存储
 * 3. 异步 POST 签名回调报文到商户通知地址，失败按指数退避重试（对齐微信重试节奏）；
 *    重试仍失败不丢数据——商户可通过查单（queryOrder）对账补偿
 *
 * <p><b>状态持久化（多实例正确性的前提）</b>：预支付单与已扣款单存放在 {@code wechat_pay_order} 表，
 * 而不是本进程的内存里。原先是内存态，带来两个真实问题：</p>
 * <ol>
 *   <li>用户确认扣款的请求落到<b>另一个实例</b>时"预支付单不存在"，付不了款；</li>
 *   <li>另一个实例的对账任务查不到扣款记录，会把<b>已扣款订单当作未付款</b>——
 *       轻则漏补偿，重则被超时任务取消（钱已收、单已取消）。</li>
 * </ol>
 * <p>改为入库后状态由数据库共享：任意实例都能确认扣款与查单，重启也不丢。
 * 扣款用「status=1 → 2」的条件更新完成，因此重复确认、并发确认只有一个成功。</p>
 *
 * <p><b>仍留在实例内的部分（写清楚）</b>：异步回调的<b>发送</b>仍由处理确认扣款的那个实例执行
 * （内存线程池 + 指数退避）。该实例若在重试期间崩溃，这次回调尝试就丢了——
 * 但支付单已落库为「已扣款」，对账任务（任意实例）查单即可补偿。
 * 也就是说：<b>回调是尽力而为，对账才是保证</b>。</p>
 *
 * <p>仅限本地/演示环境使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPaySimulator {

    private final WechatPayMockProperties properties;
    private final WechatPayOrderMapper wechatPayOrderMapper;

    /** 模拟微信异步通知的调度线程（单线程保证同一实例内回调串行发送） */
    private final ScheduledExecutorService notifyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wxpay-mock-notify");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已扣款支付单（模拟微信侧支付订单，查单/对账依据） */
    @Data
    public static class PaidOrder {
        private String outTradeNo;
        private Long orderId;
        private BigDecimal amount;
        private String transactionId;
        private LocalDateTime payTime;
    }

    /**
     * 模拟微信统一下单：校验金额，签发 prepayId 并生成前端调起支付所需的参数
     *
     * <p>同一订单重复发起支付时，把该订单此前「待确认扣款」的预支付单置为已作废——
     * 避免用户对着一个已废弃的弹窗付款而系统不认识它。</p>
     */
    public WechatPayParamsVO unifiedOrder(OrderInfo order) {
        if (order.getPayAmount() == null
                || order.getPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单支付金额非法，无法发起支付");
        }
        String prepayId = "wx" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(100000, 999999);

        wechatPayOrderMapper.update(null, new LambdaUpdateWrapper<WechatPayOrder>()
                .eq(WechatPayOrder::getOutTradeNo, order.getOrderNo())
                .eq(WechatPayOrder::getStatus, WechatPayOrder.STATUS_PREPAID)
                .set(WechatPayOrder::getStatus, WechatPayOrder.STATUS_VOIDED));

        WechatPayOrder prepay = new WechatPayOrder();
        prepay.setPrepayId(prepayId);
        prepay.setOutTradeNo(order.getOrderNo());
        prepay.setOrderId(order.getId());
        prepay.setAmount(order.getPayAmount());
        prepay.setStatus(WechatPayOrder.STATUS_PREPAID);
        wechatPayOrderMapper.insert(prepay);

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
     * 模拟用户在微信侧确认扣款：以「预支付 → 已扣款」的条件更新落库，再异步回调商户通知地址。
     *
     * <p>条件更新是刻意的：并发/重复确认（用户连点、请求重放）只有一个能改成功，
     * 因此不会重复扣款、也不会重复发回调。</p>
     *
     * @return false 表示预支付单不存在或已消费（过期/重复确认）
     */
    public boolean confirmPay(String prepayId) {
        if (prepayId == null || prepayId.isEmpty()) {
            return false;
        }
        String transactionId = "TX" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        LocalDateTime payTime = LocalDateTime.now();
        WechatPayOrder paid = new WechatPayOrder();
        paid.setStatus(WechatPayOrder.STATUS_PAID);
        paid.setTransactionId(transactionId);
        paid.setPayTime(payTime);
        int affected = wechatPayOrderMapper.update(paid, new LambdaUpdateWrapper<WechatPayOrder>()
                .eq(WechatPayOrder::getPrepayId, prepayId)
                .eq(WechatPayOrder::getStatus, WechatPayOrder.STATUS_PREPAID));
        if (affected <= 0) {
            return false;
        }
        WechatPayOrder row = wechatPayOrderMapper.selectOne(new LambdaQueryWrapper<WechatPayOrder>()
                .eq(WechatPayOrder::getPrepayId, prepayId)
                .last("LIMIT 1"));
        if (row == null) {
            return false;
        }
        // 微信侧先记支付单（查单依据），再通知商户——与真实链路一致：
        // 即使回调全部丢失，商户查单仍能拿到支付成功结果
        notifyExecutor.schedule(() -> sendNotifyWithRetry(toPaidOrder(row), 0),
                properties.getNotifyDelayMs(), TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * 模拟微信查单接口：商户主动查询订单在微信侧的支付结果
     *
     * @return null 表示微信侧无该订单的扣款记录（未支付）；非空为已扣款（含回调未送达/送达失败）
     */
    public PaidOrder queryOrder(String outTradeNo) {
        if (outTradeNo == null) {
            return null;
        }
        List<WechatPayOrder> rows = wechatPayOrderMapper.selectList(new LambdaQueryWrapper<WechatPayOrder>()
                .eq(WechatPayOrder::getOutTradeNo, outTradeNo)
                .eq(WechatPayOrder::getStatus, WechatPayOrder.STATUS_PAID)
                .orderByDesc(WechatPayOrder::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : toPaidOrder(rows.get(0));
    }

    private PaidOrder toPaidOrder(WechatPayOrder row) {
        PaidOrder paid = new PaidOrder();
        paid.setOutTradeNo(row.getOutTradeNo());
        paid.setOrderId(row.getOrderId());
        paid.setAmount(row.getAmount());
        paid.setTransactionId(row.getTransactionId());
        paid.setPayTime(row.getPayTime());
        return paid;
    }

    /**
     * 发送签名回调报文到商户通知地址；失败按指数退避重试（1s/2s/4s/8s/16s，对齐微信重试节奏）。
     * 重试耗尽后支付单仍保留在库中，商户对账任务/查单接口可补偿。
     */
    private void sendNotifyWithRetry(PaidOrder paid, int attempt) {
        boolean success = false;
        try {
            WechatPayNotifyRequest body = new WechatPayNotifyRequest();
            body.setOutTradeNo(paid.getOutTradeNo());
            body.setTransactionId(paid.getTransactionId());
            body.setAmount(paid.getAmount());
            body.setPayTime(paid.getPayTime().format(TIME_FMT));
            body.setResultCode("SUCCESS");

            String json = objectMapper.writeValueAsString(body);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 模拟微信支付签名机制：商户端需验签后才处理
            headers.set("Wechatpay-Signature", hmacSha256(json));
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    properties.getNotifyUrl(), new HttpEntity<>(json, headers), String.class);
            // 与微信应答约定一致：HTTP 200 且报文 SUCCESS 才算送达，其余一律视为失败进入重试
            success = resp.getStatusCode().is2xxSuccessful() && "SUCCESS".equals(resp.getBody());
            if (success) {
                log.info("[模拟微信支付] 回调商户通知地址完成，outTradeNo={}，商户响应={}",
                        paid.getOutTradeNo(), resp.getBody());
                return;
            }
            log.warn("[模拟微信支付] 回调商户应答非 SUCCESS，outTradeNo={}，响应={}",
                    paid.getOutTradeNo(), resp.getBody());
        } catch (Exception e) {
            log.warn("[模拟微信支付] 回调商户通知地址失败，outTradeNo={}，第 {} 次尝试：{}",
                    paid.getOutTradeNo(), attempt + 1, e.getMessage());
        }
        if (attempt >= properties.getNotifyMaxRetries()) {
            log.error("[模拟微信支付] 回调重试耗尽（共 {} 次），outTradeNo={}；"
                            + "订单保持待支付，等待商户查单对账补偿（对应真实链路微信持续重试+商户对账）",
                    attempt + 1, paid.getOutTradeNo());
            return;
        }
        long backoffMs = properties.getNotifyRetryBaseMs() * (1L << attempt);
        notifyExecutor.schedule(() -> sendNotifyWithRetry(paid, attempt + 1),
                backoffMs, TimeUnit.MILLISECONDS);
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
