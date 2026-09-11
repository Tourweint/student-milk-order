package com.milk.order.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.order.dto.CreateOrderRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.vo.OrderVO;

import java.util.List;

public interface OrderInfoService extends IService<OrderInfo> {

    /** 分页查询订单（多条件筛选），返回附带关联名称的 OrderVO */
    IPage<OrderVO> pageOrders(Long pageNum, Long pageSize, Long classId, Long studentId,
                               Integer status, String startDate, String endDate);

    /** 订单详情（含明细） */
    OrderVO getOrderDetail(Long id);

    /** 创建订单（生成订单号、计算金额、保存明细，状态为待支付，不扣库存） */
    Long createOrder(CreateOrderRequest request);

    /** 续订订单：复制原订单明细，生成新配送周期的订单并自动支付，返回新订单ID */
    Long renewOrder(Long originalOrderId);

    /** 模拟支付（同事务：写支付记录 + 订单改已支付 + 扣减库存） */
    void payOrder(Long id);

    /** 退订（待支付/已支付可退；已支付的退订回库） */
    void cancelOrder(Long id, String reason);

    /** 开始配送（已支付 → 配送中） */
    void startDelivery(Long id);

    /** 完成订单（配送中 → 已完成） */
    void completeOrder(Long id);

    /** 查询订单明细 */
    List<OrderItem> getOrderItems(Long orderId);
}
