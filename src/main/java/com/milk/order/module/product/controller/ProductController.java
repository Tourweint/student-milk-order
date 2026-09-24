package com.milk.order.module.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.product.dto.DailyQuotaBatchRequest;
import com.milk.order.module.product.dto.PackageSaveRequest;
import com.milk.order.module.product.vo.BatchTraceVO;
import com.milk.order.module.product.vo.QuotaVO;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductBatch;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.product.service.MealPackageService;
import com.milk.order.module.product.service.ProductBatchService;
import com.milk.order.module.product.service.ProductCategoryService;
import com.milk.order.module.product.service.ProductService;
import com.milk.order.module.product.vo.MealPackageDetailVO;
import com.milk.order.module.product.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 奶品管理控制器
 *
 * 接口清单：
 * 品类：GET/POST/PUT/DELETE /api/product/category[...]
 * 奶品：GET /api/product/list、GET/POST/PUT/DELETE /api/product[...]
 * 套餐：GET/POST/PUT/DELETE /api/product/package[...]（仅学期套餐）
 * 每日机动配额（单日零散订购用，无传统仓库库存）：
 * - GET  /api/product/quota/list           配额列表（日期区间）
 * - GET  /api/product/quota/remaining      某日某品种剩余机动盒数
 * - GET  /api/product/quota/remaining/list 某日全品种剩余机动盒数
 * - PUT  /api/product/quota/batch          批量设置某日机动配额（发行入口，受仓库余量 R5′ 封顶）
 * 批次追溯钩子（仅召回反查，不参与业务流转）：
 * - GET    /api/product/batch/list       批次列表
 * - POST   /api/product/batch            新增批次
 * - PUT    /api/product/batch            修改批次
 * - DELETE /api/product/batch/{id}       删除批次（逻辑删除）
 * - GET    /api/product/batch/trace      批号召回反查（批号 → 配额池 → 台账 → 订单/学生/任务）
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductCategoryService categoryService;
    private final ProductService productService;
    private final MealPackageService packageService;
    private final DailyQuotaService dailyQuotaService;
    private final ProductBatchService productBatchService;

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
    public ApiResponse<ProductVO> getProductById(@PathVariable Long id) {
        return ApiResponse.success(productService.getProductDetail(id));
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
    public ApiResponse<MealPackageDetailVO> getPackageById(@PathVariable Long id) {
        return ApiResponse.success(packageService.getPackageDetail(id));
    }

    @PostMapping("/package")
    public ApiResponse<Void> savePackage(@Valid @RequestBody PackageSaveRequest request) {
        packageService.createPackage(request);
        return ApiResponse.success();
    }

    @PutMapping("/package")
    public ApiResponse<Void> updatePackage(@Valid @RequestBody PackageSaveRequest request) {
        packageService.updatePackage(request);
        return ApiResponse.success();
    }

    @DeleteMapping("/package/{id}")
    public ApiResponse<Void> deletePackage(@PathVariable Long id) {
        packageService.removePackage(id);
        return ApiResponse.success();
    }

    // ==================== 库存与流水 ====================
    // 已废弃传统仓库库存（入库/出库/盘点），仅保留【每日机动配额】支撑单日零散订购

    @GetMapping("/quota/list")
    public ApiResponse<List<QuotaVO>> quotaList(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        LocalDate start = StringUtils.hasText(startDate) ? LocalDate.parse(startDate) : null;
        LocalDate end = StringUtils.hasText(endDate) ? LocalDate.parse(endDate) : null;
        return ApiResponse.success(dailyQuotaService.listRange(start, end));
    }

    @GetMapping("/quota/remaining")
    public ApiResponse<Integer> quotaRemaining(@RequestParam Long productId, @RequestParam String date) {
        return ApiResponse.success(dailyQuotaService.remaining(productId, LocalDate.parse(date)));
    }

    @GetMapping("/quota/remaining/list")
    public ApiResponse<List<QuotaVO>> quotaRemainingList(@RequestParam String date) {
        return ApiResponse.success(dailyQuotaService.remainingList(LocalDate.parse(date)));
    }

    @PutMapping("/quota/batch")
    public ApiResponse<Void> setQuotaBatch(@Valid @RequestBody DailyQuotaBatchRequest request) {
        dailyQuotaService.setQuotaBatch(request.getQuotaDate(), request.getItems(), request.getRemark());
        return ApiResponse.success();
    }

    // ==================== 批次追溯钩子（仅召回反查，不参与业务流转） ====================

    @GetMapping("/batch/list")
    public ApiResponse<List<ProductBatch>> batchList(@RequestParam(required = false) Long productId,
                                                     @RequestParam(required = false) Integer status) {
        return ApiResponse.success(productBatchService.listBatches(productId, status));
    }

    @PostMapping("/batch")
    public ApiResponse<Void> saveBatch(@RequestBody ProductBatch batch) {
        productBatchService.createBatch(batch);
        return ApiResponse.success();
    }

    @PutMapping("/batch")
    public ApiResponse<Void> updateBatch(@RequestBody ProductBatch batch) {
        productBatchService.updateBatch(batch);
        return ApiResponse.success();
    }

    @DeleteMapping("/batch/{id}")
    public ApiResponse<Void> deleteBatch(@PathVariable Long id) {
        productBatchService.deleteBatch(id);
        return ApiResponse.success();
    }

    /** 批号召回反查：批号 → 配额池（日期×品种）→ 扣减台账 → 订单/学生/配送任务与签收状态 */
    @GetMapping("/batch/trace")
    public ApiResponse<BatchTraceVO> traceBatch(@RequestParam String batchNo) {
        return ApiResponse.success(productBatchService.trace(batchNo));
    }
}
