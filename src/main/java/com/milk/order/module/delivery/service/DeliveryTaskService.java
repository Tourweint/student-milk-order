package com.milk.order.module.delivery.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.dto.StockoutCancelRequest;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.delivery.vo.PendingSignVO;
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

    /** 该订单下是否存在任何任务（不变量求值用：「无任务」不能被当作「任务已全部终态」） */
    boolean hasAnyTask(Long orderId);

    /** 该订单下是否所有任务都已取消，且至少存在一条任务 */
    boolean hasAllTasksCancelled(Long orderId);

    /**
     * 不变量修复（INV_TASK_RECORD）：把「任务已完成但签收记录未签收」的记录补齐为已签收。
     * 经统一迁移出口执行并留痕，幂等，返回是否生效。
     */
    boolean repairRecordSigned(Long recordId);

    /**
     * 不变量修复（INV_SIGN_INTAKE）：为「已签收但缺营养摄入记录」的记录补生成摄入记录。
     * 幂等（已存在则跳过），返回是否生效。
     */
    boolean repairIntakeForRecord(Long recordId);

    /** 任务详情 */
    DeliveryTaskVO getTaskDetail(Long id);

    /** 开始配送（待配送→配送中） */
    void startDelivery(Long taskId);

    /** 取消任务（待配送/配送中→已取消） */
    void cancelTask(Long taskId, String reason);

    /**
     * 配送前缺货批量取消：取消某配送日期某奶品的全部「待配送」任务（单期子订单取消）。
     * 已完成/配送中任务不受影响（禁止回退）；关联零散订单当日配额按台账回补；
     * 不影响该订单其他期次；订单任务全部终态时自动完成。返回取消任务数。
     */
    int stockoutCancel(StockoutCancelRequest request);

    /** 签收（未签收→已签收，任务→已完成，同时生成营养摄入记录）；仅可签收已开始配送（已送出）的任务 */
    void signRecord(SignRequest request);

    /** 订单退订联动：取消该订单下全部未完成任务的未签收记录，返回取消任务数 */
    int cancelPendingTasksForOrder(Long orderId);

    /** 拒收（未签收→拒收，任务→已取消）；仅可拒收已开始配送（已送出）的任务 */
    void rejectRecord(Long recordId, String reason);

    /** 配送记录分页（日期/班级/学生/签收状态筛选） */
    IPage<DeliveryRecordVO> pageRecords(Long pageNum, Long pageSize, String deliveryDate, Long classId,
                                         Long studentId, Integer signStatus);

    /**
     * 自动签收兜底候选：返回配送日期早于 today、任务已送出（配送中）且记录未签收的配送记录 ID。
     * 供次日凌晨定时任务批量调用（单条独立事务处理，单条失败不影响其余）。
     */
    List<Long> listExpiredAutoSignRecordIds(int limit);

    /**
     * 单条自动签收（独立事务、幂等）：仅签收「配送日期早于 today + 任务已送出 + 记录未签收」的记录，
     * 签收人标记为系统自动签收；不满足任一前置条件（含已处理）静默跳过，不抛异常。
     */
    void autoSignOne(Long recordId);

    /**
     * 某配送日期（默认今天）「已送出未签收」待签收汇总：按班级聚合记录数；
     * 班主任数据范围强制限定本班，管理员/配送站可看全部班级。返回 total 与班级明细。
     */
    PendingSignVO pendingSign(String deliveryDate);
}
