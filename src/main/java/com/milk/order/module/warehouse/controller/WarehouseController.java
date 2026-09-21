package com.milk.order.module.warehouse.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.warehouse.dto.WarehouseAdjustRequest;
import com.milk.order.module.warehouse.dto.WarehouseReceiptRequest;
import com.milk.order.module.warehouse.entity.WarehouseLedger;
import com.milk.order.module.warehouse.service.WarehouseService;
import com.milk.order.module.warehouse.vo.WarehouseBalanceVO;
import com.milk.order.module.warehouse.vo.WarehouseReceiptVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 仓库余量台账接口（供给侧）。
 *
 * <p>角色收紧在 {@code SecurityConfig} 统一声明（本项目不使用 {@code @PreAuthorize}）：
 * 到货登记与余量查询 = 配送站 + 管理员；台账查询与修正 = 仅管理员（家长/班主任不接触仓库）。</p>
 *
 * <p>台账**只读**：本控制器不提供修改与删除接口；记错走 `adjustment` 的反向冲销（两条留痕）。</p>
 */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    /** 登记到货（配送站收货点数；响应带短交预警，预警不阻断登记） */
    @PostMapping("/receipt")
    public ApiResponse<WarehouseReceiptVO> receipt(@Valid @RequestBody WarehouseReceiptRequest request) {
        return ApiResponse.success(warehouseService.receipt(request));
    }

    /** 当前余量（按品种列出 W） */
    @GetMapping("/balance")
    public ApiResponse<List<WarehouseBalanceVO>> balance() {
        return ApiResponse.success(warehouseService.balance());
    }

    /** 台账查询（类型 / 品种 / 日期筛选，分页） */
    @GetMapping("/ledger")
    public ApiResponse<PageResult<WarehouseLedger>> ledger(@RequestParam(required = false) Long pageNum,
                                                          @RequestParam(required = false) Long pageSize,
                                                          @RequestParam(required = false) String bizType,
                                                          @RequestParam(required = false) Long productId,
                                                          @RequestParam(required = false) String startDate,
                                                          @RequestParam(required = false) String endDate) {
        return ApiResponse.success(PageResult.of(
                warehouseService.pageLedger(pageNum, pageSize, bizType, productId, startDate, endDate)));
    }

    /** 修正（仅管理员，必填原因）：正数增加余量、负数减少余量 */
    @PostMapping("/adjustment")
    public ApiResponse<Void> adjustment(@Valid @RequestBody WarehouseAdjustRequest request) {
        warehouseService.adjust(request);
        return ApiResponse.success();
    }
}
