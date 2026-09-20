package com.milk.order.module.delivery.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.delivery.dto.DeliveryExceptionDeleteRequest;
import com.milk.order.module.delivery.dto.DeliveryExceptionSaveRequest;
import com.milk.order.module.delivery.entity.DeliveryException;
import com.milk.order.module.delivery.service.DeliveryExceptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 配送例外控制器（停送日 / 补课日，仅管理员）。
 *
 * 接口清单：
 * - GET  /api/delivery/exception/list    查询例外（可按日期范围）
 * - POST /api/delivery/exception/save    新增/修改例外（日期唯一，撞键报错）
 * - POST /api/delivery/exception/delete  删除例外
 */
@RestController
@RequestMapping("/api/delivery/exception")
@RequiredArgsConstructor
public class DeliveryExceptionController {

    private final DeliveryExceptionService deliveryExceptionService;

    @GetMapping("/list")
    public ApiResponse<List<DeliveryException>> list(@RequestParam(required = false) String startDate,
                                                     @RequestParam(required = false) String endDate) {
        return ApiResponse.success(deliveryExceptionService.listRange(startDate, endDate));
    }

    @PostMapping("/save")
    public ApiResponse<Long> save(@Valid @RequestBody DeliveryExceptionSaveRequest request) {
        return ApiResponse.success(deliveryExceptionService.save(request));
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody DeliveryExceptionDeleteRequest request) {
        deliveryExceptionService.delete(request.getId());
        return ApiResponse.success();
    }
}
