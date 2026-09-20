package com.milk.order.module.delivery.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.milk.order.common.ApiResponse;
import com.milk.order.common.PageResult;
import com.milk.order.module.delivery.dto.BatchSignRequest;
import com.milk.order.module.delivery.dto.BatchStartRequest;
import com.milk.order.module.delivery.dto.RebalanceRequest;
import com.milk.order.module.delivery.dto.ShiftTasksRequest;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.dto.StockoutCancelRequest;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.delivery.vo.ParentHomeVO;
import com.milk.order.module.delivery.vo.PendingSignVO;
import com.milk.order.module.delivery.vo.ShiftResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 配送管理控制器
 *
 * 接口清单：
 * - POST   /api/delivery/task/generate     按日期生成配送任务（可选班级，手工补生成用；支付成功后系统自动生成整期任务）
 * - GET    /api/delivery/task/list          配送任务分页（日期/班级/状态）
 * - GET    /api/delivery/task/{id}          任务详情
 * - PUT    /api/delivery/task/start/{id}    开始配送（待配送→配送中，记录派送人/时间并联动订单）
 * - PUT    /api/delivery/task/batch-start   今日已送出：按日期批量开始配送（幂等，退款闸门联动；ADMIN/DELIVERY）
 * - POST   /api/delivery/task/shift         配送日平移（仅管理员；两级预检 + 合并/新建到目标日）
 * - POST   /api/delivery/task/rebalance     学期末摊平（仅管理员；按截止日重排待配送任务数量）
 * - GET    /api/delivery/task/summary       某配送日期按班级汇总（配送站面板今日概览）
 * - GET    /api/delivery/task/pending-quantity 家长端首页：绑定学生的剩余待配送盒数（待配送+配送中）
 * - PUT    /api/delivery/task/cancel/{id}   取消任务
 * - PUT    /api/delivery/task/stockout-cancel 配送前缺货批量取消（日期+奶品，仅待配送任务，配额回补）
 * - GET    /api/delivery/record/list         配送记录分页（日期/班级/学生/签收状态）
 * - POST   /api/delivery/record/sign         签收（同时任务完成+生成营养摄入）
 * - POST   /api/delivery/record/batch-sign   批量签收（按日期+可选班级，班主任限本班）
 * - POST   /api/delivery/record/reject       拒收（写结构化原因，并在同事务内按订单类型落补送）
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
            @RequestParam(required = false) String dateEnd,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Integer status) {
        IPage<DeliveryTaskVO> page = deliveryTaskService.pageTasks(
                pageNum, pageSize, deliveryDate, dateEnd, orderNo, classId, status);
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

    /** 今日已送出：按日期（可选班级）批量开始配送，联动订单进入配送中（退款闸门） */
    @PutMapping("/task/batch-start")
    public ApiResponse<Integer> batchStartTasks(@Valid @RequestBody BatchStartRequest request) {
        return ApiResponse.success(deliveryTaskService.batchStartDelivery(request.getDeliveryDate(), request.getClassId()));
    }

    /**
     * 配送日平移（仅管理员）：把待配送任务平移到目标日；目标日已有同订单同品种任务则合并数量，否则新建。
     * 执行前两级预检（源任务配送中 / 目标日任务已开始配送），命中即拒绝整批。
     */
    @PostMapping("/task/shift")
    public ApiResponse<ShiftResultVO> shiftTasks(@Valid @RequestBody ShiftTasksRequest request) {
        return ApiResponse.success(deliveryTaskService.shiftTasksToDate(request.getTaskIds(), request.getTargetDate()));
    }

    /** 学期末摊平（仅管理员）：按截止日把订单剩余量重排到 [今天, 截止日] 的待配送任务上（可重复执行，幂等） */
    @PostMapping("/task/rebalance")
    public ApiResponse<Integer> rebalance(@Valid @RequestBody RebalanceRequest request) {
        return ApiResponse.success(deliveryTaskService.adjustQuantitiesBeforeDeadline(
                request.getOrderId(), request.getDeadline()));
    }

    /** 某配送日期按班级汇总任务状态数量（配送站面板今日概览） */
    @GetMapping("/task/summary")
    public ApiResponse<List<DailyDispatchSummaryVO>> taskSummary(@RequestParam String deliveryDate) {
        return ApiResponse.success(deliveryTaskService.dailySummary(deliveryDate));
    }

    /** 家长端首页：当前登录家长绑定学生的「剩余待配送」盒数（未完成＝待配送+配送中，数据范围为绑定学生） */
    @GetMapping("/task/pending-quantity")
    public ApiResponse<Integer> pendingQuantity() {
        return ApiResponse.success(deliveryTaskService.pendingQuantityForCurrentStudent());
    }

    /** 家长端首页聚合：剩余待配送盒数 + 下次配送日 + 近期拒收（数据范围限定为绑定学生） */
    @GetMapping("/task/parent-home")
    public ApiResponse<ParentHomeVO> parentHome() {
        return ApiResponse.success(deliveryTaskService.parentHomeOverview());
    }

    /** 配送前缺货批量取消：取消某日期某奶品全部待配送任务（仅取消该期，不影响已完成任务） */
    @PutMapping("/task/stockout-cancel")
    public ApiResponse<Integer> stockoutCancel(@Valid @RequestBody StockoutCancelRequest request) {
        int count = deliveryTaskService.stockoutCancel(request);
        return ApiResponse.success(count);
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

    /** 待签收汇总：某配送日期（默认今天）「已送出未签收」记录按班级聚合；班主任限本班，用于提醒与一键签收入口 */
    @GetMapping("/record/pending-sign")
    public ApiResponse<PendingSignVO> pendingSign(@RequestParam(required = false) String deliveryDate) {
        return ApiResponse.success(deliveryTaskService.pendingSign(deliveryDate));
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

    /**
     * 拒收：写结构化原因（reasonCode/reasonDetail）并在同事务内落补送。
     * reason 为兼容旧调用方的自由文本，可空。
     */
    @PostMapping("/record/reject")
    public ApiResponse<Void> rejectRecord(@RequestParam Long recordId,
                                          @RequestParam(required = false) String reasonCode,
                                          @RequestParam(required = false) String reasonDetail,
                                          @RequestParam(required = false) String reason) {
        deliveryTaskService.rejectRecord(recordId, reasonCode, reasonDetail, reason);
        return ApiResponse.success();
    }
}
