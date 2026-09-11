package com.milk.order.module.stats.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.stats.service.StatsService;
import com.milk.order.module.stats.vo.ClassRankingVO;
import com.milk.order.module.stats.vo.CoverageVO;
import com.milk.order.module.stats.vo.DashboardVO;
import com.milk.order.module.stats.vo.TrendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据可视化控制器
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/dashboard")
    public ApiResponse<DashboardVO> dashboard() {
        return ApiResponse.success(statsService.dashboard());
    }

    @GetMapping("/order/trend")
    public ApiResponse<TrendVO> orderTrend(
            @RequestParam(defaultValue = "day") String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(statsService.orderTrend(type, startDate, endDate));
    }

    @GetMapping("/order/category")
    public ApiResponse<List<Map<String, Object>>> orderCategory() {
        return ApiResponse.success(statsService.orderCategory());
    }

    @GetMapping("/order/class-ranking")
    public ApiResponse<List<ClassRankingVO>> classRanking(@RequestParam(defaultValue = "10") Integer limit) {
        return ApiResponse.success(statsService.classRanking(limit));
    }

    @GetMapping("/nutrition/dashboard")
    public ApiResponse<Map<String, Object>> nutritionDashboard(@RequestParam(required = false) Long classId) {
        return ApiResponse.success(statsService.nutritionDashboard(classId));
    }

    @GetMapping("/coverage")
    public ApiResponse<CoverageVO> coverage() {
        return ApiResponse.success(statsService.coverage());
    }
}
