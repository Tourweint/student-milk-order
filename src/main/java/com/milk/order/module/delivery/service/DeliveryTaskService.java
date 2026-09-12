package com.milk.order.module.delivery.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.order.entity.OrderInfo;

public interface DeliveryTaskService extends IService<DeliveryTask> {

    /** 按日期批量生成配送任务（从已支付且在配送区间内的订单展开，用于手工补生成），返回生成数量 */
    int generateTasks(String deliveryDate, Long classId);

    /** 为单个已支付订单展开整个配送周期的任务与签收记录（支付成功后自动调用，幂等），返回生成数量 */
    int generateTasksForOrder(OrderInfo order);

    /** 批量签收：签收某配送日期（可选限定班级）下全部未签收记录，班主任强制限定本班，返回签收数量 */
    int batchSign(String deliveryDate, Long classId);

    /** 配送任务分页（日期/班级/状态筛选，回填关联名称） */
    IPage<DeliveryTaskVO> pageTasks(Long pageNum, Long pageSize, String deliveryDate, Long classId, Integer status);

    /** 任务详情 */
    DeliveryTaskVO getTaskDetail(Long id);

    /** 开始配送（待配送→配送中） */
    void startDelivery(Long taskId);

    /** 取消任务（待配送/配送中→已取消） */
    void cancelTask(Long taskId, String reason);

    /** 签收（未签收→已签收，任务→已完成，同时生成营养摄入记录） */
    void signRecord(SignRequest request);

    /** 订单退订联动：取消该订单下全部未完成任务的未签收记录，返回取消任务数 */
    int cancelPendingTasksForOrder(Long orderId);

    /** 拒收（未签收→拒收，任务→已取消） */
    void rejectRecord(Long recordId, String reason);

    /** 配送记录分页（日期/班级/学生/签收状态筛选） */
    IPage<DeliveryRecordVO> pageRecords(Long pageNum, Long pageSize, String deliveryDate, Long classId,
                                         Long studentId, Integer signStatus);
}
