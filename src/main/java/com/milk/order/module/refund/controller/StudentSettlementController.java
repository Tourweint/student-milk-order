package com.milk.order.module.refund.controller;

import com.milk.order.common.ApiResponse;
import com.milk.order.module.refund.service.RefundOrderService;
import com.milk.order.module.refund.vo.SettlementResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 毕业清算控制器（仅管理员，SecurityConfig 对 /api/student/** 单独收紧）。
 *
 * 接口清单：
 * - POST /api/student/{studentId}/settle  毕业清算（幂等：重复执行只会得到"无可清算订单"）
 *
 * <p>与 {@code /api/clazz/student/**}（班级学生管理，ADMIN+TEACHER）是不同的命名空间——
 * 这里刻意不复用 clazz 前缀，避免班级管理角色顺带获得清算（涉资金）权限。</p>
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentSettlementController {

    private final RefundOrderService refundOrderService;

    @PostMapping("/{studentId}/settle")
    public ApiResponse<SettlementResultVO> settle(@PathVariable Long studentId) {
        return ApiResponse.success(refundOrderService.settleStudent(studentId));
    }
}
