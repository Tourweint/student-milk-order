package com.milk.order.module.refund.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.refund.dto.RefundApplyRequest;
import com.milk.order.module.refund.dto.RefundAuditRequest;
import com.milk.order.module.refund.service.RefundOrderService;
import com.milk.order.module.refund.vo.RefundOrderVO;
import com.milk.order.module.refund.vo.RefundPreviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 退款域控制器（场景 REFUND）。
 *
 * 接口清单（父过程：退款单）：
 * - POST   /api/refund/order/{orderId}        家长申请退款（进行中退款单存在时拒绝）
 * - GET    /api/refund/order/my               本人（家长）退款单分页
 * - GET    /api/refund/order/{orderId}/preview 退款预览（只读，与执行共用金额口径）
 * - GET    /api/refund/list                   全部退款单分页（管理员）
 * - PUT    /api/refund/{id}/audit             审核（通过 → 2；拒绝 → 4）
 * - PUT    /api/refund/{id}/execute           执行退款（先作废期次、再计价、后落账，同一事务）
 */
@RestController
@RequestMapping("/api/refund")
@RequiredArgsConstructor
public class RefundController {

    private final RefundOrderService refundOrderService;

    @PostMapping("/order/{orderId}")
    public ApiResponse<Long> applyRefund(@PathVariable Long orderId,
                                         @Valid @RequestBody RefundApplyRequest request) {
        return ApiResponse.success(refundOrderService.applyRefund(orderId, request));
    }

    @GetMapping("/order/my")
    public ApiResponse<PageResult<RefundOrderVO>> myRefunds(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Integer status) {
        IPage<RefundOrderVO> page = refundOrderService.pageMyRefunds(pageNum, pageSize, status);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/order/{orderId}/preview")
    public ApiResponse<RefundPreviewVO> preview(@PathVariable Long orderId) {
        return ApiResponse.success(refundOrderService.preview(orderId));
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<RefundOrderVO>> list(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long studentId) {
        IPage<RefundOrderVO> page = refundOrderService.pageAllRefunds(pageNum, pageSize, status, orderNo, studentId);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @PutMapping("/{id}/audit")
    public ApiResponse<Void> audit(@PathVariable Long id, @Valid @RequestBody RefundAuditRequest request) {
        refundOrderService.audit(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/execute")
    public ApiResponse<Void> execute(@PathVariable Long id) {
        refundOrderService.executeRefund(id);
        return ApiResponse.success();
    }
}
