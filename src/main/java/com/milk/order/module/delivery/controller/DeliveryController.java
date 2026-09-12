package com.milk.order.module.delivery.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.delivery.dto.BatchSignRequest;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 配送管理控制器
 *
 * 接口清单：
 * - POST   /api/delivery/task/generate     按日期生成配送任务（可选班级，手工补生成用；支付成功后系统自动生成整期任务）
 * - GET    /api/delivery/task/list          配送任务分页（日期/班级/状态）
 * - GET    /api/delivery/task/{id}          任务详情
 * - PUT    /api/delivery/task/start/{id}    开始配送（待配送→配送中）
 * - PUT    /api/delivery/task/cancel/{id}   取消任务
 * - GET    /api/delivery/record/list         配送记录分页（日期/班级/学生/签收状态）
 * - POST   /api/delivery/record/sign         签收（同时任务完成+生成营养摄入）
 * - POST   /api/delivery/record/batch-sign   批量签收（按日期+可选班级，班主任限本班）
 * - POST   /api/delivery/record/reject       拒收
 */
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryTaskService deliveryTaskService;

    @PostMapping("/task/generate")
    public ApiResponse<Integer> generateTasks(@RequestParam String deliveryDate,
                                               @RequestParam(required = false) Long classId) {
        int count = deliveryTaskService.generateTasks(deliveryDate, classId);
        return ApiResponse.success(count);
    }

    @GetMapping("/task/list")
    public ApiResponse<PageResult<DeliveryTaskVO>> taskList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) String deliveryDate,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Integer status) {
        IPage<DeliveryTaskVO> page = deliveryTaskService.pageTasks(pageNum, pageSize, deliveryDate, classId, status);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @GetMapping("/task/{id}")
    public ApiResponse<DeliveryTaskVO> getTaskById(@PathVariable Long id) {
        return ApiResponse.success(deliveryTaskService.getTaskDetail(id));
    }

    @PutMapping("/task/start/{id}")
    public ApiResponse<Void> startTask(@PathVariable Long id) {
        deliveryTaskService.startDelivery(id);
        return ApiResponse.success();
    }

    @PutMapping("/task/cancel/{id}")
    public ApiResponse<Void> cancelTask(@PathVariable Long id,
                                         @RequestParam(required = false) String reason) {
        deliveryTaskService.cancelTask(id, reason);
        return ApiResponse.success();
    }

    @GetMapping("/record/list")
    public ApiResponse<PageResult<DeliveryRecordVO>> recordList(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize,
            @RequestParam(required = false) String deliveryDate,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Integer signStatus) {
        IPage<DeliveryRecordVO> page = deliveryTaskService.pageRecords(pageNum, pageSize, deliveryDate, classId, studentId, signStatus);
        return ApiResponse.success(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @PostMapping("/record/sign")
    public ApiResponse<Void> signRecord(@Valid @RequestBody SignRequest request) {
        deliveryTaskService.signRecord(request);
        return ApiResponse.success();
    }

    @PostMapping("/record/batch-sign")
    public ApiResponse<Integer> batchSignRecords(@Valid @RequestBody BatchSignRequest request) {
        int count = deliveryTaskService.batchSign(request.getDeliveryDate(), request.getClassId());
        return ApiResponse.success(count);
    }

    @PostMapping("/record/reject")
    public ApiResponse<Void> rejectRecord(@RequestParam Long recordId,
                                           @RequestParam(required = false) String reason) {
        deliveryTaskService.rejectRecord(recordId, reason);
        return ApiResponse.success();
    }
}
