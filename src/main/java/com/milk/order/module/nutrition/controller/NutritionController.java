package com.milk.order.module.nutrition.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.nutrition.dto.NutritionInfoRequest;
import com.milk.order.module.nutrition.service.NutritionInfoService;
import com.milk.order.module.nutrition.vo.NutritionInfoVO;
import com.milk.order.module.nutrition.vo.NutritionIntakeVO;
import com.milk.order.module.nutrition.vo.NutritionSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 营养统计控制器
 *
 * 接口清单：
 * - GET    /api/nutrition/info/list           营养成分列表（回填奶品名）
 * - GET    /api/nutrition/info/{productId}    某奶品的营养成分
 * - POST   /api/nutrition/info                 新增/更新营养成分（按 productId 幂等）
 * - GET    /api/nutrition/intake/list          营养摄入记录分页（学生/日期区间）
 * - GET    /api/nutrition/intake/summary       营养摄入按日汇总（学生+日期区间）
 */
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionController {

    private final NutritionInfoService nutritionInfoService;

    @GetMapping("/info/list")
    public ApiResponse<List<NutritionInfoVO>> infoList() {
        return ApiResponse.success(nutritionInfoService.listWithProduct());
    }

    @GetMapping("/info/{productId}")
    public ApiResponse<NutritionInfoVO> getInfoByProductId(@PathVariable Long productId) {
        return ApiResponse.success(nutritionInfoService.getByProductId(productId));
    }

    @PostMapping("/info")
    public ApiResponse<Void> saveInfo(@Valid @RequestBody NutritionInfoRequest request) {
        nutritionInfoService.saveOrUpdateByProduct(request);
        return ApiResponse.success();
    }

    @GetMapping("/intake/list")
    public ApiResponse<PageResult<NutritionIntakeVO>> intakeList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        IPage<NutritionIntakeVO> page = nutritionInfoService.pageIntakes(pageNum, pageSize, studentId, startDate, endDate);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/intake/summary")
    public ApiResponse<List<NutritionSummaryVO>> intakeSummary(
            @RequestParam Long studentId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(nutritionInfoService.summaryByStudent(studentId, startDate, endDate));
    }
}
