package com.milk.order.module.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.product.dto.InventoryChangeRequest;
import com.milk.order.module.product.dto.InventoryThresholdRequest;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.service.InventoryService;
import com.milk.order.module.product.service.MealPackageService;
import com.milk.order.module.product.service.ProductCategoryService;
import com.milk.order.module.product.service.ProductService;
import com.milk.order.module.product.vo.InventoryRecordVO;
import com.milk.order.module.product.vo.InventoryVO;
import com.milk.order.module.product.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 奶品管理控制器
 *
 * 接口清单：
 * 品类：GET/POST/PUT/DELETE /api/product/category[...]
 * 奶品：GET /api/product/list、GET/POST/PUT/DELETE /api/product[...]
 * 套餐：GET/POST/PUT/DELETE /api/product/package[...]
 * 库存与流水：
 * - GET  /api/product/inventory/list          库存分页（带奶品信息）
 * - GET  /api/product/inventory/warning       库存预警列表
 * - POST /api/product/inventory/change        库存变动（入库/出库/盘盈/盘亏，写流水）
 * - PUT  /api/product/inventory/threshold     设置预警阈值/库位
 * - GET  /api/product/inventory/record/list   库存流水分页（统计数据源）
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductCategoryService categoryService;
    private final ProductService productService;
    private final MealPackageService packageService;
    private final InventoryService inventoryService;

    // ==================== 品类 ====================

    @GetMapping("/category/list")
    public ApiResponse<List<ProductCategory>> categoryList() {
        return ApiResponse.success(categoryService.listOrdered());
    }

    @PostMapping("/category")
    public ApiResponse<Void> saveCategory(@RequestBody ProductCategory category) {
        categoryService.createCategory(category);
        return ApiResponse.success();
    }

    @PutMapping("/category")
    public ApiResponse<Void> updateCategory(@RequestBody ProductCategory category) {
        categoryService.updateCategory(category);
        return ApiResponse.success();
    }

    @DeleteMapping("/category/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        categoryService.removeCategory(id);
        return ApiResponse.success();
    }

    // ==================== 奶品 ====================

    @GetMapping("/list")
    public ApiResponse<PageResult<ProductVO>> productList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        IPage<ProductVO> page = productService.pageProducts(pageNum, pageSize, categoryId, keyword);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> getProductById(@PathVariable Long id) {
        return ApiResponse.success(productService.getById(id));
    }

    @PostMapping
    public ApiResponse<Void> saveProduct(@RequestBody Product product) {
        productService.createProduct(product);
        return ApiResponse.success();
    }

    @PutMapping
    public ApiResponse<Void> updateProduct(@RequestBody Product product) {
        productService.updateProduct(product);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
        productService.removeProduct(id);
        return ApiResponse.success();
    }

    // ==================== 套餐 ====================

    @GetMapping("/package/list")
    public ApiResponse<List<MealPackage>> packageList() {
        return ApiResponse.success(packageService.listOrdered());
    }

    @GetMapping("/package/{id}")
    public ApiResponse<MealPackage> getPackageById(@PathVariable Long id) {
        return ApiResponse.success(packageService.getById(id));
    }

    @PostMapping("/package")
    public ApiResponse<Void> savePackage(@RequestBody MealPackage mealPackage) {
        packageService.createPackage(mealPackage);
        return ApiResponse.success();
    }

    @PutMapping("/package")
    public ApiResponse<Void> updatePackage(@RequestBody MealPackage mealPackage) {
        packageService.updatePackage(mealPackage);
        return ApiResponse.success();
    }

    @DeleteMapping("/package/{id}")
    public ApiResponse<Void> deletePackage(@PathVariable Long id) {
        packageService.removePackage(id);
        return ApiResponse.success();
    }

    // ==================== 库存与流水 ====================

    @GetMapping("/inventory/list")
    public ApiResponse<PageResult<InventoryVO>> inventoryList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) String keyword) {
        IPage<InventoryVO> page = inventoryService.pageInventory(pageNum, pageSize, keyword);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/inventory/warning")
    public ApiResponse<List<InventoryVO>> inventoryWarning() {
        return ApiResponse.success(inventoryService.listWarning());
    }

    @PostMapping("/inventory/change")
    public ApiResponse<Void> changeStock(@Valid @RequestBody InventoryChangeRequest request) {
        inventoryService.changeStock(request);
        return ApiResponse.success();
    }

    @PutMapping("/inventory/threshold")
    public ApiResponse<Void> updateThreshold(@RequestBody InventoryThresholdRequest request) {
        inventoryService.updateThreshold(request.getId(), request.getWarningThreshold(), request.getWarehouseLocation());
        return ApiResponse.success();
    }

    @GetMapping("/inventory/record/list")
    public ApiResponse<PageResult<InventoryRecordVO>> recordList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Integer changeType) {
        IPage<InventoryRecordVO> page = inventoryService.pageRecords(pageNum, pageSize, productId, changeType);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }
}
