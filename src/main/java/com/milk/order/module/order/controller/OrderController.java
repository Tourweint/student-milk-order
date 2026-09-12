package com.milk.order.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.order.dto.CreateOrderRequest;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.order.vo.OrderVO;
import com.milk.order.module.order.vo.WechatPayParamsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 订单核心控制器
 *
 * 接口清单：
 * - GET    /api/order/list           订单分页（班级/学生/状态/时间筛选）
 * - GET    /api/order/{id}           订单详情（含明细）
 * - POST   /api/order                 创建订单（待支付，不扣库存）
 * - POST   /api/order/prepay/{id}    微信支付预下单（模拟）：返回调起支付凭证，支付结果经微信异步回调更新
 * - POST   /api/order/pay/{id}       模拟支付（同步：写支付记录+改已支付+扣库存），供管理端与续订内部流程
 * - PUT    /api/order/cancel/{id}    退订（已支付退订回库）
 * - PUT    /api/order/deliver/{id}   开始配送（已支付→配送中）
 * - PUT    /api/order/complete/{id}  完成订单（配送中→已完成）
 * - GET    /api/order/{id}/items     订单明细
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderInfoService orderInfoService;

    @GetMapping("/list")
    public ApiResponse<PageResult<OrderVO>> list(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        IPage<OrderVO> page = orderInfoService.pageOrders(pageNum, pageSize, classId, studentId, status, startDate, endDate);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderVO> getById(@PathVariable Long id) {
        return ApiResponse.success(orderInfoService.getOrderDetail(id));
    }

    @PostMapping
    public ApiResponse<Long> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Long orderId = orderInfoService.createOrder(request);
        return ApiResponse.success(orderId);
    }

    @PostMapping("/pay/{id}")
    public ApiResponse<Void> pay(@PathVariable Long id) {
        orderInfoService.payOrder(id);
        return ApiResponse.success();
    }

    /** 微信支付预下单（模拟）：返回调起 wx.requestPayment 所需的凭证参数 */
    @PostMapping("/prepay/{id}")
    public ApiResponse<WechatPayParamsVO> prepay(@PathVariable Long id) {
        return ApiResponse.success(orderInfoService.prepayOrder(id));
    }

    @PutMapping("/cancel/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id,
                                    @RequestParam(required = false) String reason) {
        orderInfoService.cancelOrder(id, reason);
        return ApiResponse.success();
    }

    @PutMapping("/deliver/{id}")
    public ApiResponse<Void> deliver(@PathVariable Long id) {
        orderInfoService.startDelivery(id);
        return ApiResponse.success();
    }

    @PutMapping("/complete/{id}")
    public ApiResponse<Void> complete(@PathVariable Long id) {
        orderInfoService.completeOrder(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<OrderItem>> orderItems(@PathVariable Long id) {
        return ApiResponse.success(orderInfoService.getOrderItems(id));
    }
}
