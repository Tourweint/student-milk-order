package com.milk.order.module.delivery.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.order.entity.OrderInfo;

import java.util.List;

public interface DeliveryTaskService extends IService<DeliveryTask> {

    /** 按日期批量生成配送任务（从已支付且在配送区间内的订单展开，用于手工补生成），返回生成数量 */
    int generateTasks(String deliveryDate, Long classId);

    /** 为单个已支付订单展开整个配送周期的任务与签收记录（支付成功后自动调用，幂等），返回生成数量 */
    int generateTasksForOrder(OrderInfo order);

    /** 批量签收：签收某配送日期（可选限定班级）下全部未签收记录，班主任强制限定本班，返回签收数量 */
    int batchSign(String deliveryDate, Long classId);

    /** 今日已送出：按日期（可选班级）批量开始配送，幂等；逐条记录派送人/时间并联动订单已支付→配送中（退款闸门），返回开始条数 */
    int batchStartDelivery(String deliveryDate, Long classId);

    /** 配送任务分页（单日日期或区间 dateEnd、订单号、班级、状态筛选，回填关联名称；班主任限定本班） */
    IPage<DeliveryTaskVO> pageTasks(Long pageNum, Long pageSize, String deliveryDate, String dateEnd,
                                    String orderNo, Long classId, Integer status);

    /** 某配送日期按班级汇总应送/待配送/配送中/已完成/已取消数量（配送站面板今日概览） */
    List<DailyDispatchSummaryVO> dailySummary(String deliveryDate);

    /** 该订单下是否存在配送中任务（退款闸门保险：存在则不可退订） */
    boolean hasDispatchingTask(Long orderId);

    /** 该订单下是否存在未到达终态的任务（待配送/配送中）；全部终态时订单可自动完成 */
    boolean hasUnfinishedTask(Long orderId);

    /** 任务详情 */
    DeliveryTaskVO getTaskDetail(Long id);

    /** 开始配送（待配送→配送中） */
    void startDelivery(Long taskId);

    /** 取消任务（待配送/配送中→已取消） */
    void cancelTask(Long taskId, String reason);

    /** 签收（未签收→已签收，任务→已完成，同时生成营养摄入记录）；仅可签收已开始配送（已送出）的任务 */
    void signRecord(SignRequest request);

    /** 订单退订联动：取消该订单下全部未完成任务的未签收记录，返回取消任务数 */
    int cancelPendingTasksForOrder(Long orderId);

    /** 拒收（未签收→拒收，任务→已取消）；仅可拒收已开始配送（已送出）的任务 */
    void rejectRecord(Long recordId, String reason);

    /** 配送记录分页（日期/班级/学生/签收状态筛选） */
    IPage<DeliveryRecordVO> pageRecords(Long pageNum, Long pageSize, String deliveryDate, Long classId,
                                         Long studentId, Integer signStatus);
}
