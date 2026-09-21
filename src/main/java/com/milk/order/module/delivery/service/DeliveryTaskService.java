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
import com.milk.order.module.delivery.vo.ParentExemptionVO;
import com.milk.order.module.delivery.vo.ParentHomeVO;
import com.milk.order.module.delivery.vo.PendingSignVO;
import com.milk.order.module.delivery.vo.RefundableTaskVO;
import com.milk.order.module.delivery.vo.ShiftResultVO;
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
     * 当前登录家长绑定学生的「剩余待配送」盒数合计：未完成（待配送 1 + 配送中 2）任务的数量之和，
     * 已取消/已完成不计。家长端首页展示用，数据范围限定为绑定的学生。
     */
    int pendingQuantityForCurrentStudent();

    /**
     * 家长端首页聚合：剩余待配送盒数 + 下次配送日 + 近期拒收（只读，数据范围限定为绑定学生）。
     *
     * <p>与 {@link #pendingQuantityForCurrentStudent()} 同一口径；「近期拒收」只取真拒收
     * （`sign_status=3 AND reject_reason_code IS NOT NULL`），用于向家长解释剩余减少的原因。</p>
     */
    ParentHomeVO parentHomeOverview();

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

    /**
     * 退款可退期次（R1 口径，只读探测）：订单的「待配送(1)」任务 ∪ 「缺货取消」任务
     * （任务已取消(4) 且签收记录为拒收(3) 且未写 reject_reason_code —— 与 INV_TASK_COMPENSATION 同判据，
     * 缺货取消没送奶、钱应退）；真拒收（有原因分类，已补送）与平移/重排作废的任务不计入。
     *
     * <p>订单已退订(5) 时返回空：退订联动作废的任务同样"签收=拒收且无原因分类"，计入会与退订的
     * 全额退款（R4）重复退钱。已配送/已完成的期次不在此列，天然不可退。</p>
     *
     * <p>可退盒数已扣除拒收补送的免费盒（见 {@link RefundableTaskVO#getRefundableBoxes()}）。</p>
     */
    List<RefundableTaskVO> listRefundableTasks(Long orderId);

    /**
     * 退款作废单条待配送任务（R1/R3）：走统一迁移出口 {@code attempt(TASK_CANCEL, 1→4)}，
     * 返回 false 表示 CAS 落败（任务已被并发送出/取消），调用方须将其剔除、不参与计价。
     *
     * <p>作废后签收记录保持「未签收」、仅标注备注：置为拒收(3) 会让该任务在 R1 判据下
     * 重新变成"缺货取消"从而可退第二次。</p>
     */
    boolean cancelTaskForRefund(Long taskId, String refundNo);

    /**
     * 拒收（未签收→拒收，任务→已取消），并在同一事务内按订单类型落拒收补送；仅可拒收已开始配送（已送出）的任务。
     *
     * @param reasonCode   拒收原因分类（DAMAGED/SOUR/WRONG_PRODUCT/SHORTAGE/OTHER，可空）
     * @param reasonDetail 拒收详细描述（可空）
     * @param reason       兼容旧调用方的自由文本原因（可空）
     */
    void rejectRecord(Long recordId, String reasonCode, String reasonDetail, String reason);

    /** 兼容旧签名：仅自由文本原因 */
    default void rejectRecord(Long recordId, String reason) {
        rejectRecord(recordId, null, null, reason);
    }

    /**
     * 配送日平移：把指定待配送任务平移到 targetDate。
     *
     * <p>执行前两级预检：源任务存在「配送中(2)」则拒绝整批；目标日同订单同品种任务存在非「待配送(1)」则拒绝整批。
     * 通过后逐条 {@code attempt} 作废原任务并合并/新建到目标日（CAS 失败即跳过该条，不影响其余）。</p>
     *
     * @return 平移结果统计
     */
    ShiftResultVO shiftTasksToDate(List<Long> taskIds, String targetDate);

    /**
     * 配送日历重排（周末停送 + 停送日并入，仅管理员）：
     * 把 [startDate, endDate] 范围内落在「周末（且非补课日）」或「停送日」的待配送任务，
     * 按规则并到前面的工作日（周六/周日各提前 2 天；停送日并入前一个有效配送日）。
     *
     * <p>前提是 `sys_config.delivery.weekend.stop=true`，否则拒绝执行（不改动现状行为）。
     * 例外表（delivery_exception）是唯一权威：type=1 停送、type=2 补课（当天照常配送）。
     * 合并后单任务超 3 盒时继续向前找未满的工作日；找不到则跳过并计入 messages。</p>
     *
     * <p>幂等：源任务 CAS 作废（1→4）后不再是「待配送」，重复执行不会重复加量；
     * 盒数与任务数守恒（作废 1 条 + 合并/新建 1 条），**不更新订单 deliveryEndDate**。</p>
     *
     * @return 重排结果统计（复用平移结果结构）
     */
    ShiftResultVO calendarRebalance(String startDate, String endDate);

    /**
     * 学期末摊平：把订单在 [今天, 截止日] 天内 status=1 的任务按剩余量重新分配（可重复执行，幂等）。
     *
     * <p>重置基准 = 1 + 该任务补送量（从 delivery_compensation 汇总），避免抹掉拒收补送；
     * 摊平后量 = min(基础量 + 分配量, 3)。已过期的（delivery_date &lt; today）与配送中(2)的任务不动。</p>
     *
     * @return 调整的任务数
     */
    int adjustQuantitiesBeforeDeadline(Long orderId, String deadline);

    /** 配送记录分页（日期/班级/学生/签收状态筛选） */
    IPage<DeliveryRecordVO> pageRecords(Long pageNum, Long pageSize, String deliveryDate, Long classId,
                                         Long studentId, Integer signStatus);

    /**
     * 自动签收兜底候选：返回配送日期早于 today、任务已送出（配送中）且记录未签收的配送记录 ID。
     *
     * <p><b>已被奶站申报「未送达」的任务不在候选集内</b>（见 {@code DeliveryUndeliveredReport}）：
     * 兜底窗口判定用的是信息态（配送站点了"已送出"），申报表达的是物理态（奶没到校），
     * 两者混同会签出虚假签收与营养摄入。</p>
     *
     * <p>供次日凌晨定时任务批量调用（单条独立事务处理，单条失败不影响其余）。</p>
     */
    List<Long> listExpiredAutoSignRecordIds(int limit);

    /**
     * 单条自动签收（独立事务、幂等）：仅签收「配送日期早于 today + 任务已送出 + 记录未签收」的记录，
     * 签收人标记为系统自动签收；不满足任一前置条件（含已处理、**已被申报未送达**）静默跳过，不抛异常。
     */
    void autoSignOne(Long recordId);

    /**
     * 某配送日期（默认今天）「已送出未签收」待签收汇总：按班级聚合记录数；
     * 班主任数据范围强制限定本班，管理员/配送站可看全部班级。返回 total 与班级明细。
     */
    PendingSignVO pendingSign(String deliveryDate);

    /**
     * 家长端「当日豁免」概览：今天可豁免的待配送任务数与本月剩余次数（仅家长，数据范围为绑定学生）。
     * 供小程序在下单/首页判断按钮是否可用，避免点进去才被拒。
     */
    ParentExemptionVO parentExemptionOverview();

    /**
     * 家长端「当日豁免」：取消该学生**今天尚未送出**（待配送）的全部任务。
     *
     * <p>口径：上限按「学生 × 自然月」计（`delivery.parent.exemption.monthly-limit`，默认 3，配 0 即关闭），
     * 次数与取消在**同一事务**内（取消失败则次数自动回退）；取消复用统一迁移出口（规则闸门 + CAS + 台账留痕 +
     * 父过程自愈待办）；零散订购的配额按台账回补原池。</p>
     */
    ParentExemptionVO parentExemptToday(String reason);
}
