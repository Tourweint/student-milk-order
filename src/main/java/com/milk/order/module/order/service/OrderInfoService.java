package com.milk.order.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.order.dto.CreateOrderRequest;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.vo.OrderVO;
import com.milk.order.module.order.vo.WechatPayParamsVO;

import java.util.List;

public interface OrderInfoService extends IService<OrderInfo> {

    /** 分页查询订单（多条件筛选），返回附带关联名称的 OrderVO */
    IPage<OrderVO> pageOrders(Long pageNum, Long pageSize, Long classId, Long studentId,
                               Integer status, String startDate, String endDate);

    /** 订单详情（含明细） */
    OrderVO getOrderDetail(Long id);

    /** 创建订单（生成订单号、计算金额、保存明细，状态为待支付，不扣库存） */
    Long createOrder(CreateOrderRequest request);

    /** 模拟支付（同事务：抢占订单状态 + 扣减配额 + 写支付流水 + 展开配送任务），供 Web 管理端支付入口使用 */
    void payOrder(Long id);

    /** 发起微信支付（模拟）：作废旧待支付流水、生成预支付单与前端调起参数，写入待支付流水 */
    WechatPayParamsVO prepayOrder(Long id);

    /** 处理微信支付回调通知（验签后调用）：校验金额、扣库存、更新支付流水与订单状态，幂等 */
    boolean handleWechatPayNotify(WechatPayNotifyRequest notify);

    /** 支付结果查单（模拟）：待支付订单主动向微信侧查单，回调丢失时补偿落账；返回订单当前状态码 */
    Integer queryPayResult(Long id);

    /** 支付对账补偿（定时任务）：扫描存在在途待支付流水的订单，逐单查单补偿，返回补偿落账数量 */
    int reconcilePendingPayments();

    /** 待支付订单超时自动取消：先查单防误杀（已扣款则补偿落账），再按状态机规则 CAS 取消；返回是否取消 */
    boolean cancelTimeoutOrder(Long id, int timeoutMinutes);

    /** 退订（待支付/已支付可退；已支付的退订回库） */
    void cancelOrder(Long id, String reason);

    /** 任务开始配送联动：订单为已支付时置为配送中，其余状态不动作（退款闸门的统一出口）；返回是否发生迁移 */
    boolean markDeliveringIfPaid(Long orderId);

    /** 过程聚合出口：配送任务全部到达终态（已完成/已取消）时自动完成订单（仅配送中状态生效）；返回是否发生迁移 */
    boolean completeOrderIfAllTasksDone(Long orderId);

    /** 过程聚合对账补偿（定时任务）：修复「父订单状态与子任务集合不一致」的漂移；返回修复数量 */
    int reconcileOrderAggregation(int limit);

    /** 完成订单（配送中 → 已完成） */
    void completeOrder(Long id);

    /** 查询订单明细 */
    List<OrderItem> getOrderItems(Long orderId);
}
