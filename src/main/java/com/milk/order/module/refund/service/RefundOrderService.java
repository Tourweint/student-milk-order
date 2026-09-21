package com.milk.order.module.refund.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.refund.dto.RefundApplyRequest;
import com.milk.order.module.refund.dto.RefundAuditRequest;
import com.milk.order.module.refund.entity.RefundOrder;
import com.milk.order.module.refund.vo.RefundOrderVO;
import com.milk.order.module.refund.vo.RefundPreviewVO;
import com.milk.order.module.refund.vo.SettlementItemVO;
import com.milk.order.module.refund.vo.SettlementResultVO;

/**
 * 退款域父过程服务。
 *
 * <p>设计依据：{@code docs/设计方案/2026-09-21-退款与毕业清算-设计方案.md}。
 * 退款单与订单状态机解耦（订单状态零新增）：执行退款只作废可退期次，
 * 订单状态由既有父过程聚合出口自然收敛。</p>
 */
public interface RefundOrderService extends IService<RefundOrder> {

    /**
     * 家长申请退款（R6 幂等：同一订单最多一张进行中退款单，由数据库唯一键 uk_refund_active 仲裁）。
     *
     * @return 退款单 ID
     */
    Long applyRefund(Long orderId, RefundApplyRequest request);

    /** 本人（家长）退款单分页 */
    IPage<RefundOrderVO> pageMyRefunds(Long pageNum, Long pageSize, Integer status);

    /** 全部退款单分页（管理员：按状态/订单号/学生筛选） */
    IPage<RefundOrderVO> pageAllRefunds(Long pageNum, Long pageSize, Integer status, String orderNo, Long studentId);

    /** 审核：通过 → 已审核待退款(2)；拒绝 → 已拒绝(4)（可重新申请） */
    void audit(Long id, RefundAuditRequest request);

    /**
     * 执行退款（R1/R2/R3/R7/R10）：
     * 先按 R1 取候选期次 → 逐条 CAS 作废 → 按**实际作废成功集合**计价 → 零散单回补配额
     * → 退款单 CAS 2→3（require，失败整体回滚）→ 落待办触发父订单聚合。
     */
    void executeRefund(Long id);

    /** 退款预览（只读探测，与执行共用同一套金额计算方法，R7） */
    RefundPreviewVO preview(Long orderId);

    /**
     * 退订联动补台账（R4）：已支付未配送退订时创建一张全额、即时的退款记录（状态 3 已退款）。
     *
     * <p>由 {@code OrderInfoServiceImpl#cancelOrder} 在同一事务内调用（@Lazy 注入打破依赖环）。
     * 建单即终态：规则表只描述 1→2→3 的合法迁移路径，不存在"空状态→已退款"的迁移，
     * 因此这里不经过迁移出口，但订单退订本身的迁移已由过程层留痕。</p>
     *
     * @return 退款单 ID；订单无需退款（未支付）时返回 null
     */
    Long createFullRefundForCancelledOrder(OrderInfo order);

    /**
     * 毕业清算（R8）：对学生名下待支付/已支付/配送中订单逐单处理，逐单独立事务、单笔失败不阻塞其余。
     *
     * <p>待支付 → 清算专属路径取消（`attempt(CANCEL, 1→5)` + 作废待支付流水），
     * **不调用** {@code cancelTimeoutOrder}（未超时 return false 清不掉；查单对账会把"已扣款未回调"补成已支付）；
     * 已支付/配送中 → 按整单可退期次自动审核并执行退款。</p>
     */
    SettlementResultVO settleStudent(Long studentId);

    /** 清算单张订单（独立事务，由 {@link #settleStudent} 经代理逐条调用） */
    SettlementItemVO settleOneOrder(Long orderId);
}
