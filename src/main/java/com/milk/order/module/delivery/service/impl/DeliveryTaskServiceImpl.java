package com.milk.order.module.delivery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.dto.StockoutCancelRequest;
import com.milk.order.module.delivery.entity.DeliveryCompensation;
import com.milk.order.module.delivery.entity.DeliveryException;
import com.milk.order.module.delivery.entity.DeliveryParentExemption;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.mapper.DeliveryCompensationMapper;
import com.milk.order.module.delivery.mapper.DeliveryExceptionMapper;
import com.milk.order.module.delivery.mapper.DeliveryParentExemptionMapper;
import com.milk.order.module.delivery.mapper.DeliveryRecordMapper;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.delivery.mapper.DeliveryUndeliveredReportMapper;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.delivery.vo.ParentExemptionVO;
import com.milk.order.module.delivery.vo.ParentHomeVO;
import com.milk.order.module.delivery.vo.PendingSignVO;
import com.milk.order.module.delivery.vo.RefundableTaskVO;
import com.milk.order.module.delivery.vo.ShiftResultVO;

import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.system.service.SysConfigService;
import com.milk.order.module.nutrition.entity.NutritionInfo;
import com.milk.order.module.nutrition.entity.NutritionIntake;
import com.milk.order.module.nutrition.mapper.NutritionInfoMapper;
import com.milk.order.module.nutrition.mapper.NutritionIntakeMapper;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.mapper.OrderItemMapper;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import com.milk.order.module.warehouse.service.WarehouseService;
import com.milk.order.process.ProcessTransitionExecutor;
import com.milk.order.process.TransitionSpec;
import com.milk.order.process.pending.ProcessPendingTaskService;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryTaskServiceImpl extends ServiceImpl<DeliveryTaskMapper, DeliveryTask> implements DeliveryTaskService {

    private final DeliveryRecordMapper deliveryRecordMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final ProductMapper productMapper;
    private final NutritionInfoMapper nutritionInfoMapper;
    private final NutritionIntakeMapper nutritionIntakeMapper;
    private final DataScopeResolver dataScopeResolver;
    private final ProcessTransitionExecutor processTransitionExecutor;
    private final IdempotencyGuard idempotencyGuard;
    private final DailyQuotaService dailyQuotaService;
    private final ProcessPendingTaskService pendingTaskService;
    /** 仓库余量台账（供给侧）：任务送出记 OUT、拒收记 IN_BACK，均与本类的事务同提交 */
    private final WarehouseService warehouseService;
    private final DeliveryCompensationMapper deliveryCompensationMapper;
    private final DeliveryExceptionMapper deliveryExceptionMapper;
    /** 未送达申报（自动签收兜底必须排除被申报的任务，避免把"没送到"签成"已签收"） */
    private final DeliveryUndeliveredReportMapper undeliveredReportMapper;
    /** 家长端「当日豁免」次数台账（学生 × 自然月一行计数器） */
    private final DeliveryParentExemptionMapper parentExemptionMapper;
    private final SysConfigService sysConfigService;

    /**
     * 任务开始配送联动订单状态（已支付→配送中）须经 OrderInfoService 统一状态机出口；
     * OrderInfoServiceImpl 反向依赖本服务，@Lazy 注入打破构造器循环依赖
     */
    @Lazy
    @Autowired
    private OrderInfoService orderInfoService;

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern ML_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ml", Pattern.CASE_INSENSITIVE);

    /** 「周末停送」开关配置键（sys_config，管理端可在线修改） */
    private static final String CONFIG_WEEKEND_STOP = "delivery.weekend.stop";

    /** 单任务单日合并上限（与期末摊平保持一致：每天最多 3 盒） */
    private static final int MAX_DAILY_TASK_QUANTITY = 3;

    /** 日历重排向前回溯的最大天数（无解时防止无限向前找；同时限定例外表加载范围） */
    private static final int CALENDAR_MAX_LOOKBACK_DAYS = 30;

    /** {@link #relocateTask} 返回码：CAS 失败，源任务已被并发处理 */
    private static final int RELOCATE_SKIPPED = 0;

    /** {@link #relocateTask} 返回码：合并到目标日已有任务 */
    private static final int RELOCATE_MERGED = 1;

    /** {@link #relocateTask} 返回码：在目标日新建任务 */
    private static final int RELOCATE_CREATED = 2;

    /**
     * 任务号：由**业务键**确定性推导（订单 × 品种 × 配送日），而不是「时间戳 + JVM 内自增序列」。
     *
     * <p>原实现用 static 计数器生成任务号，多实例下两个实例的计数器互不知情，同一秒内可能生成同一个号。
     * 更严重的是：任务表上同时有业务唯一键 `uk_order_product_date` 和任务号唯一键 `uk_task_no`，
     * 而幂等守卫无法区分"业务键已存在（该跳过）"与"任务号撞了（该换号重试）"，
     * 会把后者误判成前者并**静默跳过**——结果是**一条配送任务凭空消失**。</p>
     *
     * <p>改为确定性生成后：任务号冲突 ⟺ 业务键冲突，两者不可能再背离；
     * 同时消除了唯一的跨实例本地状态（静态计数器）。任务号本身也变成了可读的
     * （日期 + 订单 + 品种），排查时不用反查数据库。</p>
     */
    private String buildTaskNo(Long orderId, Long productId, LocalDate deliveryDate) {
        return "DT" + deliveryDate.format(NO_DATE_FMT) + "-" + orderId + "-" + productId;
    }

    // ==================== 生成配送任务 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generateTasks(String deliveryDate, Long classId) {
        LocalDate date = LocalDate.parse(deliveryDate);
        // 1. 查已支付且配送区间覆盖该日期的订单
        LambdaQueryWrapper<OrderInfo> orderWrapper = new LambdaQueryWrapper<>();
        orderWrapper.eq(OrderInfo::getStatus, OrderStatus.PAID.getCode())
                .le(OrderInfo::getDeliveryStartDate, date)
                .ge(OrderInfo::getDeliveryEndDate, date);
        if (classId != null) {
            orderWrapper.eq(OrderInfo::getClassId, classId);
        }
        List<OrderInfo> orders = orderInfoMapper.selectList(orderWrapper);
        if (orders.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (OrderInfo order : orders) {
            count += generateTasksForOrderDate(order, date);
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generateTasksForOrder(OrderInfo order) {
        LocalDate start = order.getDeliveryStartDate();
        LocalDate end = order.getDeliveryEndDate();
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        Map<Long, Integer> demandByProduct = collectDailyDemand(order.getId());
        if (demandByProduct.isEmpty()) {
            return 0;
        }
        // 首次展开任务时快照「合同总盒数」（退款金额分母基准，只写一次，见方法注释）
        snapshotContractTotalBoxes(order, demandByProduct, start, end);
        int count = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            count += createTasksForDate(order, date, demandByProduct);
        }
        return count;
    }

    /**
     * 快照「合同总盒数」到 {@code order_info.contract_total_boxes}（退款金额的分母基准）。
     *
     * <p>必须快照而不能在退款时实时求和：平移/重排会把源任务 CAS 作废但保留行、拒收补送会加量或新建任务
     * （免费盒，不额外收费）、期末摊平会重写 quantity —— 三者都会让实时 {@code SUM(quantity)} 漂移，
     * 分母一变退款比例就算错。</p>
     *
     * <p>取值由「每日需求盒数 × 配送天数」确定性推导，与任务是否已存在无关，因此支付回调重复到达、
     * 不变量补网格（{@link #generateTasksForOrder}）再次调用时都不会写出第二个值；
     * 只在列为空时写入一次（条件更新 {@code IS NULL}，多实例并发下也只有一个值落库）。</p>
     */
    private void snapshotContractTotalBoxes(OrderInfo order, Map<Long, Integer> demandByProduct,
                                            LocalDate start, LocalDate end) {
        if (order.getContractTotalBoxes() != null) {
            return;
        }
        int dailyBoxes = demandByProduct.values().stream().mapToInt(Integer::intValue).sum();
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        long total = dailyBoxes * days;
        if (total <= 0) {
            return;
        }
        orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, order.getId())
                .isNull(OrderInfo::getContractTotalBoxes)
                .set(OrderInfo::getContractTotalBoxes, (int) total));
        order.setContractTotalBoxes((int) total);
    }

    /** 为单个订单展开某一日期的任务（generateTasks 手工补生成复用） */
    private int generateTasksForOrderDate(OrderInfo order, LocalDate date) {
        return createTasksForDate(order, date, collectDailyDemand(order.getId()));
    }

    /**
     * 汇总订单的每日需求：按品种合并数量。
     *
     * <p>同一订单可能出现同品种的多条明细（购物车重复加购），必须先合并再展开任务，
     * 否则同一 (订单, 品种, 配送日期) 会命中业务唯一键，导致第 2 条明细的数量被吞掉。</p>
     */
    private Map<Long, Integer> collectDailyDemand(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        Map<Long, Integer> demand = new LinkedHashMap<>();
        for (OrderItem item : items) {
            demand.merge(item.getProductId(),
                    item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
        }
        return demand;
    }

    private int createTasksForDate(OrderInfo order, LocalDate date, Map<Long, Integer> demandByProduct) {
        int count = 0;
        for (Map.Entry<Long, Integer> demand : demandByProduct.entrySet()) {
            count += createTaskIfAbsent(order, demand.getKey(), demand.getValue(), date);
        }
        return count;
    }

    /**
     * 创建某订单某奶品某日期的任务与签收记录。
     *
     * <p>幂等由数据库唯一键 uk_order_product_date 仲裁：并发场景（重复支付回调、手工补生成与
     * 自动展开撞车）下只有一方插入成功，另一方得到唯一键冲突并幂等跳过，任务数量恒等于理论数量。
     * 原先的“先 selectCount 再 insert”在并发下会双双查不到、再双双插入，是实验三暴露的缺陷。</p>
     *
     * <p>任务号由业务键确定性生成（见 {@link #buildTaskNo}），因此 uk_task_no 与 uk_order_product_date
     * 不可能出现"一个冲突、另一个不冲突"的情形——「冲突即跳过」这个判定在多实例下依然成立。</p>
     */
    private int createTaskIfAbsent(OrderInfo order, Long productId, int quantity, LocalDate date) {
        return createTaskReturning(order, productId, quantity, date) == null ? 0 : 1;
    }

    /**
     * 创建任务并返回实体（已存在时返回 null）。
     *
     * <p>平移与拒收补送需要在拿到新任务 id 后继续写补偿台账 / 联动签收记录，
     * 因此这里返回实体，而不只是「是否成功」。</p>
     */
    private DeliveryTask createTaskReturning(OrderInfo order, Long productId, int quantity, LocalDate date) {
        DeliveryTask task = new DeliveryTask();
        task.setTaskNo(buildTaskNo(order.getId(), productId, date));
        task.setDeliveryDate(date);
        task.setClassId(order.getClassId());
        task.setOrderId(order.getId());
        task.setStudentId(order.getStudentId());
        task.setProductId(productId);
        task.setQuantity(quantity);
        task.setStatus(1); // 待配送
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(task));
        if (!inserted) {
            return null; // 已存在（并发重复生成）：幂等跳过
        }
        // 创建签收记录
        DeliveryRecord record = new DeliveryRecord();
        record.setTaskId(task.getId());
        record.setStudentId(order.getStudentId());
        record.setProductId(productId);
        record.setQuantity(quantity);
        record.setSignStatus(2); // 未签收
        deliveryRecordMapper.insert(record);
        return task;
    }

    // ==================== 任务查询 ====================

    @Override
    public IPage<DeliveryTaskVO> pageTasks(Long pageNum, Long pageSize, String deliveryDate, String dateEnd,
                                           String orderNo, Long classId, Integer status) {
        // 数据权限：班主任仅能查看本班配送任务，管理员/配送站不限
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getClassId() != null) {
            classId = scope.getClassId();
        }
        Page<DeliveryTask> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<DeliveryTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(deliveryDate), DeliveryTask::getDeliveryDate, deliveryDate)
                // 与 deliveryDate 组合成日期区间：deliveryDate ~ dateEnd
                .le(StringUtils.hasText(dateEnd), DeliveryTask::getDeliveryDate, dateEnd)
                .eq(classId != null, DeliveryTask::getClassId, classId)
                .eq(status != null, DeliveryTask::getStatus, status)
                .orderByDesc(DeliveryTask::getId);
        if (StringUtils.hasText(orderNo)) {
            List<Long> matchedOrderIds = orderInfoMapper.selectList(
                            new LambdaQueryWrapper<OrderInfo>().like(OrderInfo::getOrderNo, orderNo))
                    .stream().map(OrderInfo::getId).collect(Collectors.toList());
            if (matchedOrderIds.isEmpty()) {
                Page<DeliveryTaskVO> empty = new Page<>(page.getCurrent(), page.getSize(), 0);
                empty.setRecords(Collections.emptyList());
                return empty;
            }
            wrapper.in(DeliveryTask::getOrderId, matchedOrderIds);
        }
        IPage<DeliveryTask> taskPage = page(page, wrapper);
        List<DeliveryTaskVO> voList = convertTasks(taskPage.getRecords());

        Page<DeliveryTaskVO> result = new Page<>(taskPage.getCurrent(), taskPage.getSize(), taskPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public DeliveryTaskVO getTaskDetail(Long id) {
        DeliveryTask task = getById(id);
        if (task == null) {
            throw new BusinessException("配送任务不存在");
        }
        return convertTasks(Collections.singletonList(task)).get(0);
    }

    // ==================== 任务状态流转 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startDelivery(Long taskId) {
        DeliveryTask task = getTask(taskId);
        // 过程层严格迁移：规则禁止（如已完成任务）抛异常，CAS 与并发批量送出竞争失败也抛异常
        processTransitionExecutor.require(dispatchSpec(task), () -> casDispatch(task));
        // 送出即出库：状态抢占成功后才记 OUT（同一事务）——落败方在上面已抛异常，不会入账
        recordOutStock(task);
        // 与批量口径保持一致：单条送出同样要联动订单已支付→配送中。
        // 原先单条路径缺这一步，会让订单停在已支付而子任务已在配送中——正是父状态漂移的一类来源，
        // 修复后由兜底通道仍可发现并纠正，但正常路径就不该产生漂移。
        orderInfoService.markDeliveringIfPaid(task.getOrderId());
        enqueueOrderAggregation(task.getOrderId(), StateTransitions.ACTION_DISPATCH);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchStartDelivery(String deliveryDate, Long classId) {
        if (!StringUtils.hasText(deliveryDate)) {
            throw new BusinessException("请选择配送日期");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(deliveryDate);
        } catch (Exception e) {
            throw new BusinessException("配送日期格式不正确");
        }
        // 仅对待配送任务生效，重复点击幂等
        List<DeliveryTask> tasks = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getDeliveryDate, date)
                .eq(DeliveryTask::getStatus, 1)
                .eq(classId != null, DeliveryTask::getClassId, classId));
        if (tasks.isEmpty()) {
            return 0;
        }
        // 状态机规则：与单条开始配送同口径，管理端禁用 待配送→配送中 时批量同样拒绝
        processTransitionExecutor.requireAllowed(StateTransitions.SCENE_DELIVERY_TASK,
                StateTransitions.ACTION_DISPATCH, 1, "配送任务");
        Set<Long> orderIds = new LinkedHashSet<>();
        int dispatched = 0;
        for (DeliveryTask task : tasks) {
            // 过程层宽松迁移：并发重复点击/批量与单条撞车时仅一方生效，更新失败按幂等跳过
            if (processTransitionExecutor.attempt(dispatchSpec(task), () -> casDispatch(task))) {
                dispatched++;
                // 送出即出库（仓库台账 OUT，同一事务）：只有本轮真正抢到状态的一方入账，
                // 重复批量送出由 uk_biz_ref 兜底（一个任务只出一次库）
                recordOutStock(task);
            }
            // 无论本轮是否由本请求送出，该订单都应联动进入配送中（订单侧幂等）
            orderIds.add(task.getOrderId());
        }
        // 退款闸门：任务开始配送即联动订单已支付→配送中，此后订单不可自助退订；
        // 同时落实时待办——即使同步联动被规则表临时挡住，秒级消费者也会按最新状态补上
        for (Long orderId : orderIds) {
            orderInfoService.markDeliveringIfPaid(orderId);
            enqueueOrderAggregation(orderId, StateTransitions.ACTION_DISPATCH);
        }
        return dispatched;
    }

    /** 开始配送的迁移规格（单条与批量共用同一收口） */
    private TransitionSpec dispatchSpec(DeliveryTask task) {
        return TransitionSpec.builder()
                .scene(StateTransitions.SCENE_DELIVERY_TASK)
                .action(StateTransitions.ACTION_DISPATCH)
                .sceneText("配送任务")
                .entityType("delivery_task")
                .entityId(task.getId())
                .bizNo(task.getTaskNo())
                .fromStatus(task.getStatus())
                .toStatus(2)
                .conflictMessage("任务状态已变更，请刷新后重试")
                .remark("任务开始配送（待配送→配送中），记录派送人与派送时间")
                .build();
    }

    /**
     * 任务开始配送的 CAS 条件更新：待配送(1)→配送中(2)，同时记录派送人与派送时间（审计痕迹）。
     */
    private boolean casDispatch(DeliveryTask task) {
        return lambdaUpdate()
                .eq(DeliveryTask::getId, task.getId())
                .eq(DeliveryTask::getStatus, 1)
                .set(DeliveryTask::getStatus, 2)
                .set(DeliveryTask::getDispatchBy, SecurityUtils.getCurrentUsername())
                .set(DeliveryTask::getDispatchTime, LocalDateTime.now())
                .update();
    }

    /**
     * 任务送出即出库（仓库台账 OUT，与状态迁移同一事务）。
     *
     * <p><b>为什么挂在这里：</b>单条 {@link #startDelivery} 与批量 {@link #batchStartDelivery}
     * 走的是同一个 {@code casDispatch}（同一状态机收口）——钩子只挂批量会让**单条送出全部漏账**。
     * 因此两处 CAS 成功后都调用本方法，口径只在这里定义一次。</p>
     *
     * <p><b>出库时点为什么是"送出"而不是签收：</b>物理移动发生在"仓库 → 领取点"，
     * 签收是消费确认。两者分开，"拒收退回"的账才有解释（见设计方案 §10 决策 3）。</p>
     *
     * <p>{@code biz_date} 取任务配送日期（业务日账，补登过去日期不会错位）；
     * 实际送出时刻由 {@code dispatch_time} 记录。幂等由 {@code uk_biz_ref} 仲裁。</p>
     */
    private void recordOutStock(DeliveryTask task) {
        warehouseService.recordOutStock(task.getId(), task.getProductId(), task.getDeliveryDate(),
                task.getQuantity() == null ? 0 : task.getQuantity());
    }

    /** 任务状态的通用 CAS 条件更新：以 fromStatus 为条件推进到 toStatus */
    private boolean casTaskStatus(Long taskId, Integer fromStatus, Integer toStatus) {
        return lambdaUpdate()
                .eq(DeliveryTask::getId, taskId)
                .eq(DeliveryTask::getStatus, fromStatus)
                .set(DeliveryTask::getStatus, toStatus)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(Long taskId, String reason) {
        DeliveryTask task = getTask(taskId);
        doCancelTask(task, StringUtils.hasText(reason) ? reason : task.getRemark());
    }

    /**
     * 任务取消的统一落账（供 {@code cancelTask} 与家长端「当日豁免」共用）。
     *
     * <p>调用方须已在事务中（含前置校验与数据权限校验）。过程层严格迁移：规则表白名单
     * （待配送/配送中可取消，已完成/已取消禁止）替代原先硬编码判断；CAS 以读取时的来源状态为条件，
     * 与并发签收/开始配送竞争，仅一方生效——否则「读状态→判断→全量更新」会让已完成(3)的任务
     * 被并发取消回退为已取消(4)。</p>
     */
    private void doCancelTask(DeliveryTask task, String cancelReason) {
        Long taskId = task.getId();
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_TASK)
                        .action(StateTransitions.ACTION_TASK_CANCEL)
                        .sceneText("配送任务")
                        .entityType("delivery_task")
                        .entityId(taskId)
                        .bizNo(task.getTaskNo())
                        .fromStatus(task.getStatus())
                        .toStatus(4)
                        .conflictMessage("任务状态已变更，请刷新后重试")
                        .remark(StringUtils.hasText(cancelReason) ? cancelReason : "任务取消")
                        .build(),
                () -> lambdaUpdate()
                        .eq(DeliveryTask::getId, taskId)
                        .eq(DeliveryTask::getStatus, task.getStatus())
                        .set(DeliveryTask::getStatus, 4)
                        .set(DeliveryTask::getRemark, cancelReason)
                        .update());
        // 实时通道：任务已到终态，父订单需要重新聚合（同事务内落待办）
        enqueueOrderAggregation(task.getOrderId(), StateTransitions.ACTION_TASK_CANCEL);
        // 同步取消关联签收记录（仅未签收记录生效，不影响已签收/已拒收结果）
        markRecordRejected(taskId, "任务已取消");
        // 任务全部到达终态时自动完成订单（父状态聚合出口）
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
    }

    // ==================== 家长端「当日豁免」 ====================

    /** 「当日豁免」每月次数上限配置键（sys_config，管理端可在线修改；配成 0 即关闭该能力） */
    private static final String CONFIG_PARENT_EXEMPTION_LIMIT = "delivery.parent.exemption.monthly-limit";

    /** 月键格式（豁免次数按学生 × 自然月计数） */
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Override
    public ParentExemptionVO parentExemptionOverview() {
        Long studentId = currentParentStudentId();
        LocalDate today = LocalDate.now();
        int limit = parentExemptionMonthlyLimit();
        int used = currentExemptionUsed(studentId, today);
        ParentExemptionVO vo = new ParentExemptionVO();
        vo.setDeliveryDate(today);
        vo.setExemptableCount(listTodayPendingTasks(studentId, today).size());
        vo.setMonthlyLimit(limit);
        vo.setUsedCount(used);
        vo.setRemaining(Math.max(0, limit - used));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParentExemptionVO parentExemptToday(String reason) {
        Long studentId = currentParentStudentId();
        LocalDate today = LocalDate.now();
        int limit = parentExemptionMonthlyLimit();
        if (limit <= 0) {
            throw new BusinessException("家长端「当日豁免」未开放（次数上限为 0），如需使用请联系学校管理员");
        }
        // 只豁免「今天 + 尚未送出（待配送）」的任务：已送出的奶在途，取消等于把已出库的奶从记录里抹掉
        List<DeliveryTask> tasks = listTodayPendingTasks(studentId, today);
        if (tasks.isEmpty()) {
            throw new BusinessException("今天没有可豁免的待配送任务（可能已送出或本就无配送）");
        }
        // 先抢次数（行锁 + 条件更新），再执行取消：次数与取消同事务，取消失败则次数自动回退
        int used = consumeExemptionQuota(studentId, today, limit);
        String cancelReason = StringUtils.hasText(reason) ? reason : "家长当日豁免";
        for (DeliveryTask task : tasks) {
            // 逐条走同一取消出口：规则闸门 + CAS + 迁移台账留痕 + 父过程自愈待办
            doCancelTask(task, cancelReason);
        }
        // 配额按台账回补原池（零散订购才有扣减台账；学期套餐不占配额，此处自动 no-op）。
        // 按 (订单, 品种) 去重：同一订单同一品种可能有多条任务，回补口径是"订单×品种"整份（与缺货取消一致）
        Set<String> restoredKeys = new HashSet<>();
        for (DeliveryTask task : tasks) {
            if (task.getOrderId() == null || task.getProductId() == null) {
                continue;
            }
            if (restoredKeys.add(task.getOrderId() + "#" + task.getProductId())) {
                dailyQuotaService.restoreForOrderProductDate(task.getOrderId(), task.getProductId(), task.getDeliveryDate());
            }
        }
        log.warn(String.format("[当日豁免] 学生 %s 豁免 %s 的 %d 条待配送任务（本月第 %d/%d 次）：%s",
                studentId, today, tasks.size(), used, limit, cancelReason));
        ParentExemptionVO vo = new ParentExemptionVO();
        vo.setDeliveryDate(today);
        vo.setExemptableCount(0);
        vo.setMonthlyLimit(limit);
        vo.setUsedCount(used);
        vo.setRemaining(Math.max(0, limit - used));
        return vo;
    }

    /** 家长数据范围：仅本人绑定的学生 */
    private Long currentParentStudentId() {
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() == null) {
            throw new BusinessException("请先绑定学生信息");
        }
        return scope.getStudentId();
    }

    /** 某学生某日「尚未送出」的待配送任务 */
    private List<DeliveryTask> listTodayPendingTasks(Long studentId, LocalDate date) {
        return baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getStudentId, studentId)
                .eq(DeliveryTask::getDeliveryDate, date)
                .eq(DeliveryTask::getStatus, TASK_PENDING));
    }

    private int parentExemptionMonthlyLimit() {
        // 配成 0 表示关闭该能力（管理端可随时关停）；负数视为非法，回落默认值 3
        int configured = sysConfigService.getInt(CONFIG_PARENT_EXEMPTION_LIMIT, 3);
        return configured < 0 ? 3 : configured;
    }

    private int currentExemptionUsed(Long studentId, LocalDate date) {
        DeliveryParentExemption counter = parentExemptionMapper.selectOne(
                new LambdaQueryWrapper<DeliveryParentExemption>()
                        .eq(DeliveryParentExemption::getStudentId, studentId)
                        .eq(DeliveryParentExemption::getExemptMonth, date.format(MONTH_FMT)));
        return counter == null || counter.getUsedCount() == null ? 0 : counter.getUsedCount();
    }

    /**
     * 抢占一次「当日豁免」名额（行锁读 + 条件更新），返回使用后的次数。
     *
     * <p>与配额池同一套并发语义：计数器是资源，`COUNT(*) &lt; N` 的判定在并发下会超限。
     * 计数行不存在时先插入（`uk_student_month` 仲裁并发插入），撞键则回读行锁再累加。</p>
     */
    private int consumeExemptionQuota(Long studentId, LocalDate date, int limit) {
        String month = date.format(MONTH_FMT);
        DeliveryParentExemption counter = parentExemptionMapper.selectForUpdate(studentId, month);
        if (counter == null) {
            DeliveryParentExemption created = new DeliveryParentExemption();
            created.setStudentId(studentId);
            created.setExemptMonth(month);
            created.setUsedCount(1);
            if (idempotencyGuard.insertIgnoringDuplicate(() -> parentExemptionMapper.insert(created))) {
                return 1;
            }
            // 并发下另一事务刚插入同一 (学生, 月份)：回读行锁后按条件更新累加
            counter = parentExemptionMapper.selectForUpdate(studentId, month);
            if (counter == null) {
                throw new BusinessException("豁免次数记录读取失败，请重试");
            }
        }
        int used = counter.getUsedCount() == null ? 0 : counter.getUsedCount();
        if (used >= limit) {
            throw new BusinessException("本月「当日豁免」次数已用完（" + used + "/" + limit + " 次）");
        }
        boolean updated = parentExemptionMapper.update(null, new LambdaUpdateWrapper<DeliveryParentExemption>()
                .eq(DeliveryParentExemption::getId, counter.getId())
                .eq(DeliveryParentExemption::getUsedCount, used)
                .apply("used_count + 1 <= {0}", limit)
                .setSql("used_count = used_count + 1")) > 0;
        if (!updated) {
            throw new BusinessException("本月「当日豁免」次数已用完（" + used + "/" + limit + " 次）");
        }
        return used + 1;
    }

    /**
     * 将任务关联的「未签收」记录置为拒收（条件更新：仅 sign_status=2 生效），
     * 已完成签收/已拒收的记录不受影响，避免并发下覆盖既有结果
     */
    private void markRecordRejected(Long taskId, String remark) {
        // 逐条经过程层执行器提交（宽松迁移），使「每一次状态迁移都留痕」这一不变量在联动路径上也成立；
        // 否则退订/缺货取消联动作废签收记录时，台账里查不到这次状态变更。
        List<DeliveryRecord> pendingRecords = deliveryRecordMapper.selectList(
                new LambdaQueryWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getTaskId, taskId)
                        .eq(DeliveryRecord::getSignStatus, 2));
        for (DeliveryRecord record : pendingRecords) {
            processTransitionExecutor.attempt(
                    TransitionSpec.builder()
                            .scene(StateTransitions.SCENE_DELIVERY_RECORD)
                            .action(StateTransitions.ACTION_REJECT)
                            .sceneText("配送记录")
                            .entityType("delivery_record")
                            .entityId(record.getId())
                            .fromStatus(2)
                            .toStatus(3)
                            .ruleGoverned(false)
                            .remark(remark)
                            .build(),
                    () -> deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                            .eq(DeliveryRecord::getId, record.getId())
                            .eq(DeliveryRecord::getSignStatus, 2)
                            .set(DeliveryRecord::getSignStatus, 3)
                            .set(DeliveryRecord::getRemark, remark)) > 0);
        }
    }

    /** 某配送日期按班级汇总任务状态数量（配送站面板今日概览） */
    @Override
    public List<DailyDispatchSummaryVO> dailySummary(String deliveryDate) {
        if (!StringUtils.hasText(deliveryDate)) {
            throw new BusinessException("请选择配送日期");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(deliveryDate);
        } catch (Exception e) {
            throw new BusinessException("配送日期格式不正确");
        }
        List<DeliveryTask> tasks = baseMapper.selectList(
                new LambdaQueryWrapper<DeliveryTask>().eq(DeliveryTask::getDeliveryDate, date));
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, List<DeliveryTask>> byClass = tasks.stream()
                .collect(Collectors.groupingBy(DeliveryTask::getClassId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, ClassInfo> classMap = classInfoMapper.selectBatchIds(byClass.keySet()).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));
        return byClass.entrySet().stream().map(entry -> {
            DailyDispatchSummaryVO vo = new DailyDispatchSummaryVO();
            vo.setClassId(entry.getKey());
            ClassInfo c = classMap.get(entry.getKey());
            vo.setClassName(c == null ? null : c.getClassName());
            vo.setTotal(entry.getValue().size());
            vo.setPending(countStatus(entry.getValue(), 1));
            vo.setDispatching(countStatus(entry.getValue(), 2));
            vo.setCompleted(countStatus(entry.getValue(), 3));
            vo.setCancelled(countStatus(entry.getValue(), 4));
            return vo;
        }).collect(Collectors.toList());
    }

    private int countStatus(List<DeliveryTask> tasks, int status) {
        return (int) tasks.stream().filter(t -> t.getStatus() != null && t.getStatus() == status).count();
    }

    @Override
    public boolean hasDispatchingTask(Long orderId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getStatus, 2));
        return count != null && count > 0;
    }

    @Override
    public boolean hasUnfinishedTask(Long orderId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .in(DeliveryTask::getStatus, Arrays.asList(1, 2)));
        return count != null && count > 0;
    }

    @Override
    public boolean hasAnyTask(Long orderId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId));
        return count != null && count > 0;
    }

    @Override
    public boolean hasAllTasksCancelled(Long orderId) {
        Long total = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId));
        if (total == null || total == 0) {
            return false; // 无任务不算“全部取消”
        }
        Long cancelled = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getStatus, 4));
        return cancelled != null && cancelled.equals(total);
    }

    @Override
    public int pendingQuantityForCurrentStudent() {
        // 数据权限：家长仅能查看自己绑定学生的配送数据
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() == null) {
            throw new BusinessException("请先绑定学生信息");
        }
        // 未完成 = 待配送(1) + 配送中(2)；已取消(4)/已完成(3) 的奶不会再送出，不计入剩余。
        // 库内 SUM 只回传一个数（走 idx_student_status），不改接口契约
        Integer sum = baseMapper.sumPendingQuantityByStudent(scope.getStudentId());
        return sum == null ? 0 : sum;
    }

    /** 家长端首页「近期拒收」最多返回条数 */
    private static final int RECENT_REJECT_LIMIT = 5;

    /** 拒收原因分类 → 中文（与 schema.sql 的 reject_reason_code 注释保持一致） */
    private static final Map<String, String> REJECT_REASON_TEXT = Map.of(
            "DAMAGED", "包装破损",
            "SOUR", "变质异味",
            "WRONG_PRODUCT", "错发品种",
            "SHORTAGE", "数量短缺",
            "OTHER", "其他");

    @Override
    public ParentHomeVO parentHomeOverview() {
        // 数据权限：家长仅能查看自己绑定学生的配送数据
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() == null) {
            throw new BusinessException("请先绑定学生信息");
        }
        Long studentId = scope.getStudentId();
        ParentHomeVO vo = new ParentHomeVO();
        Integer sum = baseMapper.sumPendingQuantityByStudent(studentId);
        vo.setPendingQuantity(sum == null ? 0 : sum);

        // 下次配送日：该学生最早一条仍未送出（待配送）且不早于今天的任务
        List<DeliveryTask> next = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .select(DeliveryTask::getDeliveryDate)
                .eq(DeliveryTask::getStudentId, studentId)
                .eq(DeliveryTask::getStatus, 1)
                .ge(DeliveryTask::getDeliveryDate, LocalDate.now())
                .orderByAsc(DeliveryTask::getDeliveryDate)
                .last("LIMIT 1"));
        if (!next.isEmpty()) {
            vo.setNextDeliveryDate(next.get(0).getDeliveryDate());
        }

        // 近期拒收：只取真拒收（写了原因分类）；退订/缺货取消不写该字段，不会出现在这里
        List<DeliveryRecord> rejects = deliveryRecordMapper.selectList(new LambdaQueryWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getStudentId, studentId)
                .eq(DeliveryRecord::getSignStatus, 3)
                .isNotNull(DeliveryRecord::getRejectReasonCode)
                .orderByDesc(DeliveryRecord::getId)
                .last("LIMIT " + RECENT_REJECT_LIMIT));
        if (rejects.isEmpty()) {
            return vo;
        }
        Map<Long, DeliveryTask> taskMap = baseMapper.selectBatchIds(
                        rejects.stream().map(DeliveryRecord::getTaskId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DeliveryTask::getId, Function.identity()));
        Map<Long, Product> productMap = productMapper.selectBatchIds(
                        rejects.stream().map(DeliveryRecord::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<ParentHomeVO.RecentReject> recent = new ArrayList<>();
        for (DeliveryRecord r : rejects) {
            DeliveryTask t = taskMap.get(r.getTaskId());
            Product p = productMap.get(r.getProductId());
            ParentHomeVO.RecentReject item = new ParentHomeVO.RecentReject();
            item.setRecordId(r.getId());
            item.setDeliveryDate(t == null ? null : t.getDeliveryDate());
            item.setProductName(p == null ? null : p.getProductName());
            item.setReasonCode(r.getRejectReasonCode());
            String base = REJECT_REASON_TEXT.getOrDefault(r.getRejectReasonCode(), r.getRejectReasonCode());
            item.setReasonText(StringUtils.hasText(r.getRejectReasonDetail())
                    ? base + "（" + r.getRejectReasonDetail() + "）" : base);
            recent.add(item);
        }
        vo.setRecentRejects(recent);
        return vo;
    }

    // ==================== 签收 / 拒收 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signRecord(SignRequest request) {
        DeliveryRecord record = deliveryRecordMapper.selectById(request.getRecordId());
        if (record == null) {
            throw new BusinessException("配送记录不存在");
        }
        if (record.getSignStatus() != 2) {
            throw new BusinessException("仅未签收记录可签收");
        }
        DeliveryTask task = getTask(record.getTaskId());
        if (task.getStatus() == null || task.getStatus() != 2) {
            throw new BusinessException("配送站尚未送出该任务，不能签收");
        }
        String signPerson = StringUtils.hasText(request.getSignPerson())
                ? request.getSignPerson() : SecurityUtils.getCurrentUsername();
        doSign(record, signPerson, request.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSign(String deliveryDate, Long classId) {
        if (!StringUtils.hasText(deliveryDate)) {
            throw new BusinessException("请选择配送日期");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(deliveryDate);
        } catch (Exception e) {
            throw new BusinessException("配送日期格式不正确");
        }
        // 数据权限：班主任仅能批量签收本班，家长无该操作权限（接口层已限制 ADMIN/TEACHER）
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getClassId() != null) {
            classId = scope.getClassId();
        }

        // 找到该日期（可选班级）下已送出（配送中）任务的未签收记录；未送出的任务不允许代签
        LambdaQueryWrapper<DeliveryTask> taskWrapper = new LambdaQueryWrapper<>();
        taskWrapper.eq(DeliveryTask::getDeliveryDate, date)
                .eq(classId != null, DeliveryTask::getClassId, classId)
                .eq(DeliveryTask::getStatus, 2);
        List<DeliveryTask> tasks = baseMapper.selectList(taskWrapper);
        if (tasks.isEmpty()) {
            return 0;
        }
        List<DeliveryRecord> records = deliveryRecordMapper.selectList(
                new LambdaQueryWrapper<DeliveryRecord>()
                        .in(DeliveryRecord::getTaskId, tasks.stream().map(DeliveryTask::getId).collect(Collectors.toSet()))
                        .eq(DeliveryRecord::getSignStatus, 2));

        String signPerson = SecurityUtils.getCurrentUsername();
        for (DeliveryRecord record : records) {
            doSign(record, signPerson, null);
        }
        return records.size();
    }

    /**
     * 签收单条记录的共用流程：更新签收记录 → 任务完成 → 生成营养摄入记录。
     * 调用方需保证记录处于未签收状态，且在事务内。
     */
    private void doSign(DeliveryRecord record, String signPerson, String remark) {
        DeliveryTask task = getTask(record.getTaskId());
        if (task.getStatus() == null || task.getStatus() != 2) {
            throw new BusinessException("配送站尚未送出该任务，不能签收");
        }
        // 过程层严格迁移：任务 配送中(2)→已完成(3)。
        // 并发重复签收（含批量与单条撞车）仅一方成功，失败方中止并回滚本次事务，营养摄入记录不会重复生成
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_TASK)
                        .action(StateTransitions.ACTION_SIGN)
                        .sceneText("配送任务")
                        .entityType("delivery_task")
                        .entityId(task.getId())
                        .bizNo(task.getTaskNo())
                        .fromStatus(2)
                        .toStatus(3)
                        .conflictMessage("任务状态已变更，请刷新后重试")
                        .remark("签收，签收人：" + signPerson)
                        .build(),
                () -> casTaskStatus(task.getId(), 2, 3));

        // 签收记录 未签收(2)→已签收(1)：delivery_record 是未纳入规则表的子状态机，
        // 只做 CAS 与留痕（ruleGoverned=false），防止并发覆盖既有签收结果
        LocalDateTime signTime = LocalDateTime.now();
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_RECORD)
                        .action(StateTransitions.ACTION_SIGN)
                        .sceneText("配送记录")
                        .entityType("delivery_record")
                        .entityId(record.getId())
                        .fromStatus(2)
                        .toStatus(1)
                        .ruleGoverned(false)
                        .conflictMessage("该配送记录已被处理，请刷新后重试")
                        .remark("签收人：" + signPerson)
                        .build(),
                () -> deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getId, record.getId())
                        .eq(DeliveryRecord::getSignStatus, 2)
                        .set(DeliveryRecord::getSignStatus, 1)
                        .set(DeliveryRecord::getSignTime, signTime)
                        .set(DeliveryRecord::getSignPerson, signPerson)
                        .set(StringUtils.hasText(remark), DeliveryRecord::getRemark, remark)) > 0);

        record.setSignStatus(1); // 已签收
        record.setSignTime(signTime);
        record.setSignPerson(signPerson);
        task.setStatus(3); // 已完成

        // 生成营养摄入记录
        generateNutritionIntake(record, task);

        // 实时通道：任务已到终态，父订单需要重新聚合（同事务内落待办）
        enqueueOrderAggregation(task.getOrderId(), StateTransitions.ACTION_SIGN);
        // 任务全部到达终态时自动完成订单（父状态聚合出口）
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectRecord(Long recordId, String reasonCode, String reasonDetail, String reason) {
        DeliveryRecord record = deliveryRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("配送记录不存在");
        }
        if (record.getSignStatus() != 2) {
            throw new BusinessException("仅未签收记录可拒收");
        }
        DeliveryTask task = getTask(record.getTaskId());
        if (task.getStatus() == null || task.getStatus() != 2) {
            throw new BusinessException("配送站尚未送出该任务，不能拒收");
        }
        // 过程层严格迁移：任务 配送中(2)→已取消(4)，与并发签收竞争，仅一方生效（CAS 即幂等闸门）
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_TASK)
                        .action(StateTransitions.ACTION_REJECT)
                        .sceneText("配送任务")
                        .entityType("delivery_task")
                        .entityId(task.getId())
                        .bizNo(task.getTaskNo())
                        .fromStatus(2)
                        .toStatus(4)
                        .conflictMessage("任务状态已变更，请刷新后重试")
                        .remark("拒收")
                        .build(),
                () -> casTaskStatus(task.getId(), 2, 4));

        // 签收记录 未签收(2)→拒收(3)：未纳入规则表的子状态机，只做 CAS 与留痕。
        // 结构化原因只在这一条真拒收路径写入；退订/缺货取消走 markRecordRejected，不写 reject_reason_code。
        String finalReasonCode = StringUtils.hasText(reasonCode) ? reasonCode : null;
        String finalReasonDetail = StringUtils.hasText(reasonDetail) ? reasonDetail : null;
        String rejectReason = StringUtils.hasText(reason) ? reason
                : (finalReasonDetail != null ? finalReasonDetail : "拒收");
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_RECORD)
                        .action(StateTransitions.ACTION_REJECT)
                        .sceneText("配送记录")
                        .entityType("delivery_record")
                        .entityId(record.getId())
                        .fromStatus(2)
                        .toStatus(3)
                        .ruleGoverned(false)
                        .conflictMessage("该配送记录已被处理，请刷新后重试")
                        .remark(rejectReason)
                        .build(),
                () -> deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getId, record.getId())
                        .eq(DeliveryRecord::getSignStatus, 2)
                        .set(DeliveryRecord::getSignStatus, 3)
                        .set(DeliveryRecord::getSignTime, LocalDateTime.now())
                        .set(DeliveryRecord::getRemark, rejectReason)
                        .set(finalReasonCode != null, DeliveryRecord::getRejectReasonCode, finalReasonCode)
                        .set(finalReasonDetail != null, DeliveryRecord::getRejectReasonDetail, finalReasonDetail)) > 0);
        // 拒收即回仓（仓库台账 IN_BACK，同一事务）：奶被带回学校即入账。
        // 钩子只挂本方法（用户入口）——任务作废联动的 markRecordRejected 也会把未签收记录
        // 置为 sign_status=3，挂在那个共享助手上会让缺货取消/退订凭空多记退回
        // （奶根本没被拒收），并让 INV_LEDGER_REFUND_LINK 的"真拒收"判据失去意义
        warehouseService.recordInBack(record.getId(), record.getProductId(), LocalDate.now(),
                record.getQuantity() == null ? 0 : record.getQuantity(), rejectReason);

        task.setStatus(4); // 已取消

        // 拒收补送：同一事务内先落补送，再做完成判定（顺序不可颠倒，见方案 §2.1 ⑧）
        generateCompensation(task);

        // 实时通道：任务已到终态，父订单需要重新聚合（同事务内落待办）
        enqueueOrderAggregation(task.getOrderId(), StateTransitions.ACTION_REJECT);
        // 任务全部到达终态时自动完成订单（父状态聚合出口）——必须在补送落库之后
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
    }

    /**
     * 拒收补送落账（见方案 §2.3）。
     *
     * <p>顺序：定位/新建 target（拿 id）→ 写补偿台账（撞键即抛异常回滚）→ 加量（仅合并场景）→ 配额（仅零散）。
     * 补送目标日 = 原任务配送日的次日。</p>
     *
     * <ul>
     *   <li>目标日已有同订单同品种待配送任务：合并 {@code quantity += boxes}（条件更新 status=1，0 行即抛异常）；</li>
     *   <li>目标日无任务（零散订单为单日、或套餐订单在周期最后一天拒收）：新建一条任务；</li>
     *   <li>配额：仅 {@code packageId == null} 的零散订单追加，套餐不占机动池；</li>
     *   <li>幂等：由 {@code delivery_compensation.source_task_id} 唯一键仲裁（CAS 已挡在更前面）。</li>
     * </ul>
     */
    private void generateCompensation(DeliveryTask sourceTask) {
        OrderInfo order = orderInfoMapper.selectById(sourceTask.getOrderId());
        if (order == null) {
            throw new BusinessException("订单不存在，无法补送");
        }
        LocalDate targetDate = sourceTask.getDeliveryDate().plusDays(1);
        int boxes = sourceTask.getQuantity() == null ? 1 : sourceTask.getQuantity();

        // 1. 定位目标日同订单同品种、仍待配送的任务（有则合并、无则新建）
        DeliveryTask target = baseMapper.selectOne(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, sourceTask.getOrderId())
                .eq(DeliveryTask::getProductId, sourceTask.getProductId())
                .eq(DeliveryTask::getDeliveryDate, targetDate)
                .eq(DeliveryTask::getStatus, 1)
                .last("LIMIT 1"));
        boolean needCreate = target == null;
        Long targetTaskId;
        if (needCreate) {
            DeliveryTask created = createTaskReturning(order, sourceTask.getProductId(), boxes, targetDate);
            if (created == null) {
                throw new BusinessException("补送任务创建失败（目标日已存在同键任务），请稍后重试");
            }
            targetTaskId = created.getId();
        } else {
            targetTaskId = target.getId();
        }

        // 2. 写补偿台账：一个被拒收任务只允许补一次；撞唯一键抛异常回滚
        //    （不能 return —— @Transactional 里 return 是正常提交，会留下"原任务已取消但无补偿"的静默丢盒）
        DeliveryCompensation compensation = new DeliveryCompensation();
        compensation.setSourceTaskId(sourceTask.getId());
        compensation.setTargetTaskId(targetTaskId);
        compensation.setOrderId(sourceTask.getOrderId());
        compensation.setProductId(sourceTask.getProductId());
        compensation.setBoxes(boxes);
        compensation.setCompensationDate(targetDate);
        compensation.setRemark("拒收补送自任务 " + sourceTask.getTaskNo());
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(
                () -> deliveryCompensationMapper.insert(compensation));
        if (!inserted) {
            throw new BusinessException("该任务已补送过，请勿重复操作");
        }

        // 3. 合并场景在此加量（新建场景步骤 1 已按 boxes 建好，不再加）
        if (!needCreate) {
            boolean updated = lambdaUpdate()
                    .eq(DeliveryTask::getId, targetTaskId)
                    .eq(DeliveryTask::getStatus, 1)
                    .setSql("quantity = quantity + " + boxes)
                    .update();
            if (!updated) {
                throw new BusinessException("次日任务已开始配送，补送失败，请改选目标日或联系管理员");
            }
            deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getTaskId, targetTaskId)
                    .setSql("quantity = quantity + " + boxes));
        }

        // 4. 配额：仅零散订单追加（套餐不占机动池，调了会污染）
        if (order.getPackageId() == null) {
            dailyQuotaService.addCompensationBox(order.getId(), sourceTask.getProductId(), targetDate, boxes);
        }
    }

    // ==================== 配送前缺货批量取消 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int stockoutCancel(StockoutCancelRequest request) {
        LocalDate date;
        try {
            date = LocalDate.parse(request.getDeliveryDate());
        } catch (Exception e) {
            throw new BusinessException("配送日期格式不正确");
        }
        Long productId = request.getProductId();
        if (productId == null) {
            throw new BusinessException("奶品不能为空");
        }
        String reason = StringUtils.hasText(request.getReason())
                ? request.getReason() : "配送前缺货，单期取消";
        // 仅取消「待配送」任务：已完成（含已签收）任务禁止任何回退；
        // 配送中任务不动（奶已出库在途），由配送站按实际到货情况处理
        List<DeliveryTask> tasks = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getDeliveryDate, date)
                .eq(DeliveryTask::getProductId, productId)
                .eq(DeliveryTask::getStatus, 1));
        if (tasks.isEmpty()) {
            return 0;
        }
        // 状态机规则：待配送→已取消（缺货）；管理端禁用该迁移时拒绝整批操作
        processTransitionExecutor.requireAllowed(StateTransitions.SCENE_DELIVERY_TASK,
                StateTransitions.ACTION_STOCKOUT_CANCEL, 1, "配送任务");

        Set<Long> orderIds = new LinkedHashSet<>();
        int count = 0;
        for (DeliveryTask task : tasks) {
            // 过程层宽松迁移：逐条 CAS 取消，与并发的开始配送/签收竞争，失败说明该任务已流转，跳过
            boolean cancelled = processTransitionExecutor.attempt(
                    TransitionSpec.builder()
                            .scene(StateTransitions.SCENE_DELIVERY_TASK)
                            .action(StateTransitions.ACTION_STOCKOUT_CANCEL)
                            .sceneText("配送任务")
                            .entityType("delivery_task")
                            .entityId(task.getId())
                            .bizNo(task.getTaskNo())
                            .fromStatus(1)
                            .toStatus(4)
                            .remark(reason)
                            .build(),
                    () -> lambdaUpdate()
                            .eq(DeliveryTask::getId, task.getId())
                            .eq(DeliveryTask::getStatus, 1)
                            .set(DeliveryTask::getStatus, 4)
                            .set(DeliveryTask::getRemark, reason)
                            .update());
            if (!cancelled) {
                continue;
            }
            count++;
            orderIds.add(task.getOrderId());
            // 同步取消关联未签收记录（仅 sign_status=2 生效）
            markRecordRejected(task.getId(), reason);
        }
        // 关联零散订单的当日配额按台账回补（学期套餐不占配额，无台账自然跳过）：
        // 只回补该订单该品种该日期的份额，不影响同订单其他期次与其他品种
        for (Long orderId : orderIds) {
            OrderInfo order = orderInfoMapper.selectById(orderId);
            if (order != null && order.getPackageId() == null) {
                dailyQuotaService.restoreForOrderProductDate(orderId, productId, date);
            }
        }
        // 缺货只取消该期任务，不影响订单其余期次；已完成任务不受影响
        // 订单任务全部到达终态时自动完成（如散订单期即全部任务）；同事务落实时待办
        for (Long orderId : orderIds) {
            enqueueOrderAggregation(orderId, StateTransitions.ACTION_STOCKOUT_CANCEL);
            orderInfoService.completeOrderIfAllTasksDone(orderId);
        }
        return count;
    }

    // ==================== 订单退订联动 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelPendingTasksForOrder(Long orderId) {
        List<DeliveryTask> tasks = baseMapper.selectList(
                new LambdaQueryWrapper<DeliveryTask>().eq(DeliveryTask::getOrderId, orderId));
        int count = 0;
        for (DeliveryTask task : tasks) {
            if (task.getStatus() != 1 && task.getStatus() != 2) {
                continue;
            }
            // 过程层宽松迁移：与并发开始配送/签收竞争，失败说明任务已流转，跳过
            boolean cancelled = processTransitionExecutor.attempt(
                    TransitionSpec.builder()
                            .scene(StateTransitions.SCENE_DELIVERY_TASK)
                            .action(StateTransitions.ACTION_TASK_CANCEL)
                            .sceneText("配送任务")
                            .entityType("delivery_task")
                            .entityId(task.getId())
                            .bizNo(task.getTaskNo())
                            .fromStatus(task.getStatus())
                            .toStatus(4)
                            .remark("订单已退订，任务作废")
                            .build(),
                    () -> lambdaUpdate()
                            .eq(DeliveryTask::getId, task.getId())
                            .eq(DeliveryTask::getStatus, task.getStatus())
                            .set(DeliveryTask::getStatus, 4)
                            .set(DeliveryTask::getRemark, "订单已退订，任务作废")
                            .update());
            if (!cancelled) {
                continue;
            }
            // 同步取消未签收的签收记录（仅 sign_status=2 生效）
            markRecordRejected(task.getId(), "订单已退订");
            count++;
        }
        if (count > 0) {
            // 实时通道：任务已到终态，父订单（已退订）需要重新聚合核对（同事务内落待办）
            enqueueOrderAggregation(orderId, StateTransitions.ACTION_TASK_CANCEL);
        }
        return count;
    }

    // ==================== 退款域：可退期次探测 / 退款作废 ====================

    /** 任务状态：待配送 */
    private static final int TASK_PENDING = 1;
    /** 任务状态：已取消 */
    private static final int TASK_CANCELLED = 4;
    /** 签收状态：拒收 */
    private static final int SIGN_REJECTED = 3;

    @Override
    public List<RefundableTaskVO> listRefundableTasks(Long orderId) {
        if (orderId == null) {
            return Collections.emptyList();
        }
        OrderInfo order = orderInfoMapper.selectById(orderId);
        if (order == null || OrderStatus.CANCELLED.getCode().equals(order.getStatus())) {
            // R1：已退订订单的任务与"缺货取消"同形（签收=拒收且无原因分类），
            // 计入会与退订时的全额退款（R4）重复退钱——整单排除
            return Collections.emptyList();
        }
        List<DeliveryTask> candidates = new ArrayList<>();
        candidates.addAll(baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getStatus, TASK_PENDING)
                .orderByAsc(DeliveryTask::getDeliveryDate)
                .orderByAsc(DeliveryTask::getId)));
        candidates.addAll(listStockoutCancelledTasks(orderId));
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 拒收补送盒（免费补偿，不额外收费）：必须从可退盒数里扣掉，否则补送后一退款就多退钱
        Map<Long, Integer> compensationByTargetTask = compensationBoxesByTargetTask(orderId);
        Map<Long, String> productNames = productNames(candidates);
        List<RefundableTaskVO> result = new ArrayList<>(candidates.size());
        for (DeliveryTask task : candidates) {
            int quantity = task.getQuantity() == null ? 0 : task.getQuantity();
            int free = compensationByTargetTask.getOrDefault(task.getId(), 0);
            int refundable = Math.max(0, quantity - free);
            if (refundable <= 0) {
                continue; // 纯补送任务：可退 0 盒，不进可退集（不产生"0 元退款单"）
            }
            boolean pending = TASK_PENDING == task.getStatus();
            RefundableTaskVO vo = new RefundableTaskVO();
            vo.setTaskId(task.getId());
            vo.setTaskNo(task.getTaskNo());
            vo.setProductId(task.getProductId());
            vo.setProductName(productNames.get(task.getProductId()));
            vo.setDeliveryDate(task.getDeliveryDate());
            vo.setQuantity(quantity);
            vo.setRefundableBoxes(refundable);
            vo.setPending(pending);
            vo.setStatusText(pending ? "待配送" : "缺货取消（未送达，可退）");
            result.add(vo);
        }
        return result;
    }

    /**
     * 缺货取消任务：任务已取消(4) + 签收记录为拒收(3) + 未写拒收原因分类。
     *
     * <p>判据必须与 {@code INV_TASK_COMPENSATION} 保持一致（它同样用
     * {@code sign_status=3 AND reject_reason_code IS NOT NULL} 区分真拒收）。两个天然边界：</p>
     * <ul>
     *   <li>平移/重排作废的任务**不置**记录为拒收（{@link #relocateTask} 只改 remark，保持未签收），
     *       因此不会被误判为"没送奶"；</li>
     *   <li>退款自己作废的任务同样不置拒收（{@link #cancelTaskForRefund}），因此不会被退款第二次捞出来。</li>
     * </ul>
     */
    private List<DeliveryTask> listStockoutCancelledTasks(Long orderId) {
        List<DeliveryTask> cancelled = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getStatus, TASK_CANCELLED));
        if (cancelled.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> taskIds = cancelled.stream().map(DeliveryTask::getId).collect(Collectors.toList());
        Set<Long> stockoutTaskIds = deliveryRecordMapper.selectList(new LambdaQueryWrapper<DeliveryRecord>()
                        .in(DeliveryRecord::getTaskId, taskIds)
                        .eq(DeliveryRecord::getSignStatus, SIGN_REJECTED)
                        .isNull(DeliveryRecord::getRejectReasonCode))
                .stream().map(DeliveryRecord::getTaskId).collect(Collectors.toSet());
        return cancelled.stream().filter(t -> stockoutTaskIds.contains(t.getId())).collect(Collectors.toList());
    }

    /** 汇总某订单下"落在目标任务上的拒收补送盒数"（target_task_id → Σ boxes） */
    private Map<Long, Integer> compensationBoxesByTargetTask(Long orderId) {
        Map<Long, Integer> boxes = new HashMap<>();
        List<DeliveryCompensation> compensations = deliveryCompensationMapper.selectList(
                new LambdaQueryWrapper<DeliveryCompensation>().eq(DeliveryCompensation::getOrderId, orderId));
        for (DeliveryCompensation compensation : compensations) {
            if (compensation.getTargetTaskId() == null) {
                continue;
            }
            boxes.merge(compensation.getTargetTaskId(),
                    compensation.getBoxes() == null ? 0 : compensation.getBoxes(), Integer::sum);
        }
        return boxes;
    }

    private Map<Long, String> productNames(List<DeliveryTask> tasks) {
        Set<Long> productIds = tasks.stream().map(DeliveryTask::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductName, (a, b) -> a));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTaskForRefund(Long taskId, String refundNo) {
        if (taskId == null) {
            return false;
        }
        DeliveryTask task = getById(taskId);
        if (task == null || task.getStatus() == null || task.getStatus() != TASK_PENDING) {
            return false; // 已被并发送出/取消：调用方剔除、不参与计价
        }
        String remark = "退款作废（" + refundNo + "）";
        // 过程层宽松迁移（attempt）：与"开始配送"并发时只有一方成功，失败即视为该期次继续配送、不计价
        boolean cancelled = processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_TASK)
                        .action(StateTransitions.ACTION_TASK_CANCEL)
                        .sceneText("配送任务")
                        .entityType("delivery_task")
                        .entityId(task.getId())
                        .bizNo(task.getTaskNo())
                        .fromStatus(TASK_PENDING)
                        .toStatus(TASK_CANCELLED)
                        .remark(remark)
                        .build(),
                () -> lambdaUpdate()
                        .eq(DeliveryTask::getId, task.getId())
                        .eq(DeliveryTask::getStatus, TASK_PENDING)
                        .set(DeliveryTask::getStatus, TASK_CANCELLED)
                        .set(DeliveryTask::getRemark, remark)
                        .update());
        if (!cancelled) {
            return false;
        }
        // 签收记录保持「未签收」，只标注备注：若置为拒收(3)，该任务会落在 R1 的"缺货取消"判据里，
        // 下一次退款会再把同一批盒退一遍
        deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getTaskId, task.getId())
                .eq(DeliveryRecord::getSignStatus, 2)
                .set(DeliveryRecord::getRemark, remark));
        return true;
    }

    // ==================== 配送日平移 / 学期末摊平 ====================

    /**
     * 配送日平移（见方案 §1.2 / §1.3）。
     *
     * <p>预检两关：① 源任务存在「配送中(2)」→ 拒绝整批；② 目标日同订单同品种任务存在非「待配送(1)」→ 拒绝整批。
     * 通过后逐条 {@code attempt}：CAS 作废原任务（1→4，幂等闸门），成功再合并/新建到目标日；
     * 单条 CAS 失败（并发被处理）只跳过该条，不影响其余。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShiftResultVO shiftTasksToDate(List<Long> taskIds, String targetDate) {
        LocalDate target = parseDate(targetDate, "目标配送日期");
        ShiftResultVO result = new ShiftResultVO();
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BusinessException("请选择要平移的配送任务");
        }
        result.setRequested(taskIds.size());

        List<DeliveryTask> tasks = baseMapper.selectBatchIds(taskIds);
        if (tasks.isEmpty()) {
            throw new BusinessException("所选配送任务不存在");
        }
        // 预检①：源任务存在「配送中(2)」→ 拒绝整批（奶已出库在途，不能改期）
        List<DeliveryTask> delivering = tasks.stream()
                .filter(t -> t.getStatus() != null && t.getStatus() == 2)
                .collect(Collectors.toList());
        if (!delivering.isEmpty()) {
            throw new BusinessException("以下任务已在配送中，请先处理后平移："
                    + delivering.stream().map(DeliveryTask::getTaskNo).collect(Collectors.joining("、")));
        }
        // 只处理待配送(1)；已终态(3/4)跳过
        List<DeliveryTask> pending = tasks.stream()
                .filter(t -> t.getStatus() != null && t.getStatus() == 1)
                .collect(Collectors.toList());
        result.setSkipped(tasks.size() - pending.size());
        if (pending.isEmpty()) {
            result.getMessages().add("所选任务均非待配送状态，无任务可平移");
            return result;
        }
        // 预检②：目标日同订单同品种任务存在非「待配送」→ 拒绝整批
        List<Long> orderIds = pending.stream().map(DeliveryTask::getOrderId).distinct().collect(Collectors.toList());
        Map<String, DeliveryTask> targetMap = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                        .in(DeliveryTask::getOrderId, orderIds)
                        .eq(DeliveryTask::getDeliveryDate, target))
                .stream().collect(Collectors.toMap(
                        t -> t.getOrderId() + "#" + t.getProductId(), Function.identity(), (a, b) -> a));
        List<String> conflicts = new ArrayList<>();
        for (DeliveryTask t : pending) {
            DeliveryTask exist = targetMap.get(t.getOrderId() + "#" + t.getProductId());
            if (exist != null && (exist.getStatus() == null || exist.getStatus() != 1)) {
                conflicts.add(exist.getTaskNo());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BusinessException("目标日以下任务已开始配送，请改选目标日："
                    + String.join("、", conflicts));
        }
        // 逐条处理：CAS 作废原任务（幂等闸门）→ 合并/新建到目标日
        for (DeliveryTask t : pending) {
            int code = relocateTask(t, target, "配送日平移至 " + target, "已平移至 " + target, targetMap);
            if (code == RELOCATE_SKIPPED) {
                result.setSkipped(result.getSkipped() + 1);
                result.getMessages().add(t.getTaskNo() + " 已被并发处理，跳过");
            } else if (code == RELOCATE_MERGED) {
                result.setMerged(result.getMerged() + 1);
                result.setShifted(result.getShifted() + 1);
            } else {
                result.setCreated(result.getCreated() + 1);
                result.setShifted(result.getShifted() + 1);
            }
        }
        return result;
    }

    /**
     * 把一条待配送任务「作废并落到目标日」——平移与日历重排共用的落账骨架。
     *
     * <p>顺序不可颠倒：先 CAS 抢占原任务状态（1→4，幂等闸门，落败即中止，不产生副作用），
     * 再合并目标日同订单同品种任务（条件更新 status=1，加量）或新建（唯一键仲裁）。
     * 目标日合并 0 行 / 新建撞键说明目标日任务已被并发处理，抛业务异常回滚。</p>
     *
     * @param source      源任务（调用方须确保其为待配送）
     * @param target      目标配送日
     * @param taskRemark  源任务备注（留痕）
     * @param recordRemark 源任务签收记录备注
     * @param targetMap   目标日 (orderId#productId → 任务) 映射；合并/新建结果会就地更新，
     *                    使同一批次内后续同键源任务合并到同一条任务而非重复新建
     * @return {@link #RELOCATE_SKIPPED} / {@link #RELOCATE_MERGED} / {@link #RELOCATE_CREATED}
     */
    private int relocateTask(DeliveryTask source, LocalDate target, String taskRemark, String recordRemark,
                             Map<String, DeliveryTask> targetMap) {
        boolean cancelled = processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_TASK)
                        .action(StateTransitions.ACTION_TASK_CANCEL)
                        .sceneText("配送任务")
                        .entityType("delivery_task")
                        .entityId(source.getId())
                        .bizNo(source.getTaskNo())
                        .fromStatus(1)
                        .toStatus(4)
                        .remark(taskRemark)
                        .build(),
                () -> lambdaUpdate()
                        .eq(DeliveryTask::getId, source.getId())
                        .eq(DeliveryTask::getStatus, 1)
                        .set(DeliveryTask::getStatus, 4)
                        .set(DeliveryTask::getRemark, taskRemark)
                        .update());
        if (!cancelled) {
            return RELOCATE_SKIPPED;
        }
        // 原记录不置拒收（平移不是拒收），仅标注 remark，保持未签收
        deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getTaskId, source.getId())
                .eq(DeliveryRecord::getSignStatus, 2)
                .set(DeliveryRecord::getRemark, recordRemark));

        int qty = source.getQuantity() == null ? 1 : source.getQuantity();
        String key = source.getOrderId() + "#" + source.getProductId();
        DeliveryTask exist = targetMap.get(key);
        if (exist != null) {
            boolean updated = lambdaUpdate()
                    .eq(DeliveryTask::getId, exist.getId())
                    .eq(DeliveryTask::getStatus, 1)
                    .setSql("quantity = quantity + " + qty)
                    .update();
            if (!updated) {
                throw new BusinessException(exist.getTaskNo() + " 已开始配送，平移失败，请改选目标日");
            }
            deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getTaskId, exist.getId())
                    .setSql("quantity = quantity + " + qty));
            // 同步缓存里的数量：同批次后续容量判断必须看到已并入的盒数
            exist.setQuantity((exist.getQuantity() == null ? 0 : exist.getQuantity()) + qty);
            enqueueOrderAggregation(source.getOrderId(), StateTransitions.ACTION_TASK_CANCEL);
            return RELOCATE_MERGED;
        }
        OrderInfo order = orderInfoMapper.selectById(source.getOrderId());
        if (order == null) {
            throw new BusinessException("订单不存在，无法平移 " + source.getTaskNo());
        }
        DeliveryTask created = createTaskReturning(order, source.getProductId(), qty, target);
        if (created == null) {
            throw new BusinessException(source.getTaskNo() + " 平移失败：目标日已存在同键任务");
        }
        // 登记进 map：同订单同品种的后续源任务应合并到这条新任务，而不是重复新建
        targetMap.put(key, created);
        enqueueOrderAggregation(source.getOrderId(), StateTransitions.ACTION_TASK_CANCEL);
        return RELOCATE_CREATED;
    }

    /**
     * 配送日历重排（周末停送 + 停送日并入）。业务规则见 `docs/研发规范/项目开发规范.md` §5.4.2，
     * 可靠性约束见 `docs/基线文档/可靠性设计.md` §十四。
     *
     * <p>业务规则：① 周六/周日（且非补课日）各提前 2 天并入周四/周五；② 停送日并入前一个有效配送日；
     * ③ 补课日（例外 type=2）照常配送、不重排；④ 目标日合并后超 3 盒则继续向前找未满的工作日；
     * ⑤ 盒数与任务数守恒，不改订单金额/套餐盒数/deliveryEndDate。</p>
     *
     * <p>幂等与并发：源任务经 CAS 作废后不再是「待配送」，重复执行 `requested` 归零、不重复加量；
     * 与签收/开始配送并发时由 CAS 与条件更新仲裁。多实例下无本地状态（例外表与任务表为准）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShiftResultVO calendarRebalance(String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "开始日期");
        LocalDate end = parseDate(endDate, "结束日期");
        if (end.isBefore(start)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (!sysConfigService.getBool(CONFIG_WEEKEND_STOP, false)) {
            throw new BusinessException("「周末停送」未开启（系统参数 delivery.weekend.stop），"
                    + "日历重排不生效；如确需重排请先开启该开关");
        }
        LocalDate today = LocalDate.now();
        ShiftResultVO result = new ShiftResultVO();

        // 例外表：加载 [start-回溯上限, end]，覆盖可能回退到的目标日
        List<DeliveryException> exceptions = deliveryExceptionMapper.selectList(
                new LambdaQueryWrapper<DeliveryException>()
                        .ge(DeliveryException::getExceptionDate, start.minusDays(CALENDAR_MAX_LOOKBACK_DAYS))
                        .le(DeliveryException::getExceptionDate, end));
        Set<LocalDate> stopDays = new HashSet<>();
        Set<LocalDate> makeUpDays = new HashSet<>();
        for (DeliveryException e : exceptions) {
            if (e.getExceptionDate() == null || e.getType() == null) {
                continue;
            }
            if (e.getType() == DeliveryException.TYPE_STOP) {
                stopDays.add(e.getExceptionDate());
            } else if (e.getType() == DeliveryException.TYPE_MAKE_UP) {
                makeUpDays.add(e.getExceptionDate());
            }
        }

        // 目标日任务缓存：date → (orderId#productId → task)，按需加载（同一日期只查一次）
        Map<LocalDate, Map<String, DeliveryTask>> targetCache = new HashMap<>();
        int requested = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!needsCalendarRebalance(date, stopDays, makeUpDays)) {
                continue;
            }
            List<DeliveryTask> dayTasks = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                    .eq(DeliveryTask::getDeliveryDate, date)
                    .orderByAsc(DeliveryTask::getOrderId, DeliveryTask::getProductId));
            // 预检（同平移）：源日存在「配送中(2)」→ 拒绝整批（奶已出库在途，不能改期）
            List<String> dispatching = dayTasks.stream()
                    .filter(t -> t.getStatus() != null && t.getStatus() == 2)
                    .map(DeliveryTask::getTaskNo)
                    .collect(Collectors.toList());
            if (!dispatching.isEmpty()) {
                throw new BusinessException("以下任务已在配送中，请先处理后重排（" + date + "）："
                        + String.join("、", dispatching));
            }
            for (DeliveryTask t : dayTasks) {
                if (t.getStatus() == null || t.getStatus() != 1) {
                    continue; // 已终态（已完成/已取消）不参与重排
                }
                requested++;
                int qty = t.getQuantity() == null ? 1 : t.getQuantity();
                if (qty > MAX_DAILY_TASK_QUANTITY) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.getMessages().add(t.getTaskNo() + " 数量 " + qty + " 盒超出单日上限，跳过");
                    continue;
                }
                LocalDate target = resolveRebalanceTarget(t, date, qty, stopDays, makeUpDays, targetCache, today);
                if (target == null) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.getMessages().add(t.getTaskNo() + "（" + date + "）向前找不到可用工作日，跳过，请人工处理");
                    continue;
                }
                int code = relocateTask(t, target,
                        "配送日历重排至 " + target, "已重排至 " + target,
                        targetCache.computeIfAbsent(target, this::loadTasksByDate));
                if (code == RELOCATE_SKIPPED) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.getMessages().add(t.getTaskNo() + " 已被并发处理，跳过");
                } else if (code == RELOCATE_MERGED) {
                    result.setMerged(result.getMerged() + 1);
                    result.setShifted(result.getShifted() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                    result.setShifted(result.getShifted() + 1);
                }
            }
        }
        result.setRequested(requested);
        if (requested == 0) {
            result.getMessages().add("所选范围内没有需要重排的任务（周末/停送日的待配送任务为空，或已重排过）");
        }
        return result;
    }

    /** 该日期是否需要重排：周末（且非补课日）或停送日 */
    private boolean needsCalendarRebalance(LocalDate date, Set<LocalDate> stopDays, Set<LocalDate> makeUpDays) {
        if (stopDays.contains(date)) {
            return true;
        }
        return isWeekend(date) && !makeUpDays.contains(date);
    }

    /** 是否为周六/周日 */
    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /**
     * 该日期是否为「有效配送日」：不是停送日，且不是「未调休的周末」。
     * 补课日（例外 type=2）即使是周末也算有效配送日。
     */
    private boolean isValidDeliveryDay(LocalDate date, Set<LocalDate> stopDays, Set<LocalDate> makeUpDays) {
        if (stopDays.contains(date)) {
            return false;
        }
        return !isWeekend(date) || makeUpDays.contains(date);
    }

    /** 目标日 (orderId#productId → 任务) 映射，用于合并判定 */
    private Map<String, DeliveryTask> loadTasksByDate(LocalDate date) {
        return baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getDeliveryDate, date))
                .stream().collect(Collectors.toMap(
                        t -> t.getOrderId() + "#" + t.getProductId(), Function.identity(), (a, b) -> a));
    }

    /**
     * 解析重排目标日。首选目标日：周末（非补课）提前 2 天（周六→周四、周日→周五），
     * 停送日并入前一个日期；随后校验「有效配送日 + 该订单该品种合并后 ≤3 盒 + 目标日任务仍为待配送」，
     * 任一不满足则继续向前（更早）找，直到找到可用日或超出回溯上限（或早于今天）。
     *
     * @return 可用目标日；找不到返回 null
     */
    private LocalDate resolveRebalanceTarget(DeliveryTask source, LocalDate sourceDate, int qty,
                                             Set<LocalDate> stopDays, Set<LocalDate> makeUpDays,
                                             Map<LocalDate, Map<String, DeliveryTask>> targetCache,
                                             LocalDate today) {
        // 周末（非补课）提前 2 天分散到周四/周五；其余（停送日，含工作日放假）提前 1 天，再逐个向前校验
        LocalDate target = (isWeekend(sourceDate) && !makeUpDays.contains(sourceDate))
                ? sourceDate.minusDays(2)
                : sourceDate.minusDays(1);
        LocalDate earliest = sourceDate.minusDays(CALENDAR_MAX_LOOKBACK_DAYS);
        String key = source.getOrderId() + "#" + source.getProductId();
        while (!target.isBefore(earliest)) {
            if (target.isBefore(today)) {
                return null; // 不能把奶改到已经过去的日期
            }
            if (!isValidDeliveryDay(target, stopDays, makeUpDays)) {
                target = target.minusDays(1);
                continue;
            }
            DeliveryTask exist = targetCache.computeIfAbsent(target, this::loadTasksByDate).get(key);
            if (exist == null || isMergeable(exist, qty)) {
                return target;
            }
            target = target.minusDays(1);
        }
        return null;
    }

    /** 目标日已存在同键任务时，判断能否合并：任务仍待配送且合并后不超过单日上限 */
    private boolean isMergeable(DeliveryTask exist, int qty) {
        if (exist.getStatus() == null || exist.getStatus() != 1) {
            return false; // 已开始配送/已终态：不能并入
        }
        int current = exist.getQuantity() == null ? 0 : exist.getQuantity();
        return current + qty <= MAX_DAILY_TASK_QUANTITY;
    }

    /**
     * 学期末摊平（见方案 §1.6）。
     *
     * <p>可重复执行、幂等：每次都把窗口内待配送任务的**计划量**重置后再重新分配，不在此前结果上叠加。
     * 计划量 = 待配送总量 − 补送量（补送是按 {@code delivery_compensation.target_task_id} 汇总的
     * "额外物理盒数"，不参与重排但必须原样保留）；摊平后量 = min(计划量 + 补送量, 3)。
     * 若把补送量也算进待分配总量，就会在目标量上再加一次而破坏守恒。</p>
     *
     * <p>口径：K = 截止日当天及之前、仍待配送的任务数；截止日之后的**待配送**任务在本次操作中作废
     * （其盒数已提前并入窗口，不能重复配送）；已送达与已开始配送(2)的任务不动。
     * 作废不改 deliveryEndDate（方案 A），网格不变量只报缺失、不受影响。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustQuantitiesBeforeDeadline(Long orderId, String deadline) {
        if (orderId == null) {
            throw new BusinessException("订单不能为空");
        }
        if (orderInfoMapper.selectById(orderId) == null) {
            throw new BusinessException("订单不存在");
        }
        LocalDate today = LocalDate.now();
        LocalDate end = parseDate(deadline, "截止日期");
        if (end.isBefore(today)) {
            throw new BusinessException("截止日期不能早于今天");
        }
        // 订单从今天起的全部剩余任务（待配送 1 + 配送中 2）
        List<DeliveryTask> remaining = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .in(DeliveryTask::getStatus, Arrays.asList(1, 2))
                .ge(DeliveryTask::getDeliveryDate, today)
                .orderByAsc(DeliveryTask::getDeliveryDate));
        if (remaining.isEmpty()) {
            throw new BusinessException("该订单没有可摊平的剩余任务");
        }
        // 可承载窗口：截止日当天及之前的待配送任务；窗口外待配送任务将作废（盒数提前消化）
        List<DeliveryTask> window = remaining.stream()
                .filter(t -> t.getStatus() == 1 && !t.getDeliveryDate().isAfter(end))
                .collect(Collectors.toList());
        List<DeliveryTask> tail = remaining.stream()
                .filter(t -> t.getStatus() == 1 && t.getDeliveryDate().isAfter(end))
                .collect(Collectors.toList());
        if (window.isEmpty()) {
            throw new BusinessException("截止日当天及之前没有可承载的待配送任务，请延后截止日期");
        }
        // 补送量：按 target_task_id 汇总。补送是"额外的物理盒数"，不参与摊平重排，但必须原样保留
        List<Long> windowIds = window.stream().map(DeliveryTask::getId).collect(Collectors.toList());
        Map<Long, Integer> compensationMap = new HashMap<>();
        List<DeliveryCompensation> compensations = deliveryCompensationMapper.selectList(
                new LambdaQueryWrapper<DeliveryCompensation>().in(DeliveryCompensation::getTargetTaskId, windowIds));
        for (DeliveryCompensation c : compensations) {
            compensationMap.merge(c.getTargetTaskId(), c.getBoxes() == null ? 0 : c.getBoxes(), Integer::sum);
        }
        int compensationTotal = compensationMap.values().stream().mapToInt(Integer::intValue).sum();

        // 可分配的「计划盒数」= 全部待配送量 − 补送量。
        // 补送量若不剔除就会被重复计入（既算进待分配总量、又在目标量上再加一次），导致总量不守恒。
        int totalPending = remaining.stream()
                .filter(t -> t.getStatus() == 1)
                .mapToInt(t -> t.getQuantity() == null ? 0 : t.getQuantity()).sum();
        int plannedTotal = Math.max(0, totalPending - compensationTotal);
        int k = window.size();
        if (plannedTotal > 2 * k) {
            throw new BusinessException("剩余 " + plannedTotal + " 盒、截止日前的配送日仅 " + k + " 个，"
                    + "即使每天 2 盒也只能送到 " + (2 * k) + " 盒，请把截止日期延后");
        }
        if (plannedTotal < k) {
            throw new BusinessException("剩余 " + plannedTotal + " 盒少于截止日前的 " + k + " 个配送日，"
                    + "请缩短截止日期");
        }
        // 分配：计划量 floor、余数前几天各多 1 盒（计划每天 ≤2）；叠加补送后单日总量 ≤3
        int base = plannedTotal / k;
        int remainder = plannedTotal % k;
        int adjusted = 0;
        for (int i = 0; i < k; i++) {
            DeliveryTask task = window.get(i);
            int normal = base + (i < remainder ? 1 : 0);
            int compensated = compensationMap.getOrDefault(task.getId(), 0);
            int targetQuantity = Math.min(normal + compensated, 3);
            if (task.getQuantity() != null && task.getQuantity() == targetQuantity) {
                continue; // 无需变更（重复执行时自然跳过，保证幂等）
            }
            boolean updated = lambdaUpdate()
                    .eq(DeliveryTask::getId, task.getId())
                    .eq(DeliveryTask::getStatus, 1)
                    .set(DeliveryTask::getQuantity, targetQuantity)
                    .update();
            if (updated) {
                deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getTaskId, task.getId())
                        .set(DeliveryRecord::getQuantity, targetQuantity));
                adjusted++;
            }
        }
        // 截止日之后的待配送任务作废（盒数已提前并入窗口，不能重复配送）
        for (DeliveryTask t : tail) {
            processTransitionExecutor.attempt(
                    TransitionSpec.builder()
                            .scene(StateTransitions.SCENE_DELIVERY_TASK)
                            .action(StateTransitions.ACTION_TASK_CANCEL)
                            .sceneText("配送任务")
                            .entityType("delivery_task")
                            .entityId(t.getId())
                            .bizNo(t.getTaskNo())
                            .fromStatus(1)
                            .toStatus(4)
                            .remark("期末摊平：并入 " + end + " 前配送")
                            .build(),
                    () -> lambdaUpdate()
                            .eq(DeliveryTask::getId, t.getId())
                            .eq(DeliveryTask::getStatus, 1)
                            .set(DeliveryTask::getStatus, 4)
                            .set(DeliveryTask::getRemark, "期末摊平：并入 " + end + " 前配送")
                            .update());
            deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getTaskId, t.getId())
                    .eq(DeliveryRecord::getSignStatus, 2)
                    .set(DeliveryRecord::getRemark, "已并入期末摊平（截止 " + end + "）"));
            enqueueOrderAggregation(orderId, StateTransitions.ACTION_TASK_CANCEL);
        }
        return adjusted;
    }

    /** 解析 yyyy-MM-dd 日期，失败抛业务异常 */
    private LocalDate parseDate(String text, String fieldName) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(fieldName + "不能为空");
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new BusinessException(fieldName + "格式不正确");
        }
    }

    // ==================== 配送记录查询 ====================

    @Override
    public IPage<DeliveryRecordVO> pageRecords(Long pageNum, Long pageSize, String deliveryDate, Long classId,
                                                Long studentId, Integer signStatus) {
        // 数据权限：家长仅能查看自己绑定学生的配送记录，班主任仅能查看本班
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() != null) {
            studentId = scope.getStudentId();
        } else if (scope.getClassId() != null) {
            classId = scope.getClassId();
        } else if (scope.isScoped()) {
            // 家长角色但未绑定学生
            throw new BusinessException("请先绑定学生信息");
        }

        Page<DeliveryRecord> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        // 先查 task 过滤日期/班级，再查 record
        LambdaQueryWrapper<DeliveryTask> taskWrapper = new LambdaQueryWrapper<>();
        taskWrapper.eq(StringUtils.hasText(deliveryDate), DeliveryTask::getDeliveryDate, deliveryDate)
                .eq(classId != null, DeliveryTask::getClassId, classId);
        List<DeliveryTask> tasks = baseMapper.selectList(taskWrapper);
        Set<Long> taskIds = tasks.stream().map(DeliveryTask::getId).collect(Collectors.toSet());

        LambdaQueryWrapper<DeliveryRecord> recordWrapper = new LambdaQueryWrapper<>();
        recordWrapper.eq(studentId != null, DeliveryRecord::getStudentId, studentId)
                .eq(signStatus != null, DeliveryRecord::getSignStatus, signStatus);
        if (!taskIds.isEmpty()) {
            recordWrapper.in(DeliveryRecord::getTaskId, taskIds);
        } else if (StringUtils.hasText(deliveryDate) || classId != null) {
            // 日期/班级过滤后无任务，直接返回空
            Page<DeliveryRecordVO> empty = new Page<>(page.getCurrent(), page.getSize(), 0);
            empty.setRecords(Collections.emptyList());
            return empty;
        }
        recordWrapper.orderByDesc(DeliveryRecord::getId);

        IPage<DeliveryRecord> recordPage = deliveryRecordMapper.selectPage(page, recordWrapper);
        List<DeliveryRecordVO> voList = convertRecords(recordPage.getRecords());

        Page<DeliveryRecordVO> result = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    // ==================== 自动签收兜底 / 待签收汇总 ====================

    /** 自动签收人标识（与人工签收区分，保留审计痕迹） */
    private static final String AUTO_SIGN_PERSON = "系统自动签收";
    /** 自动签收备注 */
    private static final String AUTO_SIGN_REMARK = "超时未签收，系统自动签收";
    /** 自动签收单批最大处理量（防止历史脏数据把定时任务拖死） */
    private static final int MAX_AUTO_SIGN_BATCH = 200;

    @Override
    public List<Long> listExpiredAutoSignRecordIds(int limit) {
        // 兜底窗口：配送日期早于 today 且任务已送出（配送中）——今天送出的留给老师当天签收，不自动兜底
        List<DeliveryTask> tasks = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getStatus, 2)
                .lt(DeliveryTask::getDeliveryDate, LocalDate.now())
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX_AUTO_SIGN_BATCH))));
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        // 物理态 vs 信息态：被奶站申报「未送达」的任务排除出候选集——它只是"已点已送出"，
        // 物理上没送到；按超时未签收签掉会凭空生成虚假签收与营养摄入（见 DeliveryUndeliveredReport）
        tasks = excludeReportedTasks(tasks);
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        return deliveryRecordMapper.selectList(new LambdaQueryWrapper<DeliveryRecord>()
                        .in(DeliveryRecord::getTaskId,
                                tasks.stream().map(DeliveryTask::getId).collect(Collectors.toSet()))
                        .eq(DeliveryRecord::getSignStatus, 2))
                .stream().map(DeliveryRecord::getId).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoSignOne(Long recordId) {
        DeliveryRecord record = deliveryRecordMapper.selectById(recordId);
        if (record == null || record.getSignStatus() == null || record.getSignStatus() != 2) {
            return; // 已签收/已拒收/不存在：幂等跳过
        }
        DeliveryTask task = getById(record.getTaskId());
        if (task == null || task.getStatus() == null || task.getStatus() != 2) {
            return; // 任务未送出或已流转：跳过
        }
        if (task.getDeliveryDate() == null || !task.getDeliveryDate().isBefore(LocalDate.now())) {
            return; // 未到兜底窗口（当天送出的留给老师签收）：跳过
        }
        // 与候选集同一条闸门：单条入口也要挡住"已申报未送达"的任务。
        // 候选集是分批取的，两次取之间可能新插入一条申报（奶站在次日 00:30 前补报），
        // 只在候选集里过滤会漏；单条入口再判一次，才是"兜底不会签掉未送达任务"的保证。
        if (isUndeliveredReported(task.getId())) {
            log.warn(String.format("[自动签收任务] 任务 %s 已被申报未送达，跳过自动签收，交由人工跟进", task.getTaskNo()));
            return;
        }
        // 复用人工签收共用流程：记录未签收→已签收（CAS）、任务配送中→已完成、生成营养摄入、订单全终态自动完成
        doSign(record, AUTO_SIGN_PERSON, AUTO_SIGN_REMARK);
    }

    /** 从候选任务中剔除「已被申报未送达」的任务（申报是奶站对物理未送达的声明） */
    private List<DeliveryTask> excludeReportedTasks(List<DeliveryTask> tasks) {
        Set<Long> reported = reportedTaskIds(tasks.stream().map(DeliveryTask::getId).collect(Collectors.toList()));
        if (reported.isEmpty()) {
            return tasks;
        }
        return tasks.stream().filter(t -> !reported.contains(t.getId())).collect(Collectors.toList());
    }

    /** 给定任务里被申报「未送达」的任务 ID 集合 */
    private Set<Long> reportedTaskIds(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> reported = undeliveredReportMapper.selectReportedTaskIds(taskIds);
        return reported == null || reported.isEmpty() ? Collections.emptySet() : new HashSet<>(reported);
    }

    /** 单个任务是否被申报未送达 */
    private boolean isUndeliveredReported(Long taskId) {
        return !reportedTaskIds(Collections.singletonList(taskId)).isEmpty();
    }

    @Override
    public PendingSignVO pendingSign(String deliveryDate) {
        LocalDate date;
        try {
            date = StringUtils.hasText(deliveryDate) ? LocalDate.parse(deliveryDate) : LocalDate.now();
        } catch (Exception e) {
            throw new BusinessException("配送日期格式不正确");
        }
        // 数据权限：班主任仅能查看本班待签收（管理员/配送站不限）；家长角色无待签收管理视图，直接返回空
        DataScope scope = dataScopeResolver.resolve();
        Long classId = scope.getClassId();
        if (scope.getStudentId() != null) {
            PendingSignVO empty = new PendingSignVO();
            empty.setTotal(0);
            empty.setClasses(Collections.emptyList());
            return empty;
        }

        List<DeliveryTask> tasks = baseMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getDeliveryDate, date)
                .eq(DeliveryTask::getStatus, 2)
                .eq(classId != null, DeliveryTask::getClassId, classId));

        PendingSignVO vo = new PendingSignVO();
        if (tasks.isEmpty()) {
            vo.setTotal(0);
            vo.setClasses(Collections.emptyList());
            return vo;
        }
        Map<Long, DeliveryTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(DeliveryTask::getId, Function.identity()));
        List<DeliveryRecord> records = deliveryRecordMapper.selectList(
                new LambdaQueryWrapper<DeliveryRecord>()
                        .in(DeliveryRecord::getTaskId, taskMap.keySet())
                        .eq(DeliveryRecord::getSignStatus, 2));
        if (records.isEmpty()) {
            vo.setTotal(0);
            vo.setClasses(Collections.emptyList());
            return vo;
        }
        // 按班级聚合待签收记录数
        Map<Long, Long> byClass = records.stream()
                .collect(Collectors.groupingBy(r -> {
                    DeliveryTask t = taskMap.get(r.getTaskId());
                    return t == null ? null : t.getClassId();
                }, Collectors.counting()));
        byClass.remove(null);

        Set<Long> classIds = byClass.keySet();
        Map<Long, ClassInfo> classMap = classIds.isEmpty() ? Collections.emptyMap()
                : classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));

        List<PendingSignVO.ClassPending> classList = byClass.entrySet().stream()
                .map(e -> {
                    PendingSignVO.ClassPending cp = new PendingSignVO.ClassPending();
                    cp.setClassId(e.getKey());
                    ClassInfo c = classMap.get(e.getKey());
                    cp.setClassName(c == null ? null : c.getClassName());
                    cp.setCount(e.getValue().intValue());
                    return cp;
                })
                .sorted(Comparator.comparing(PendingSignVO.ClassPending::getCount).reversed())
                .collect(Collectors.toList());
        vo.setTotal(records.size());
        vo.setClasses(classList);
        return vo;
    }

    /**
     * 实时通道：在子过程状态变更的同一事务内落一条「父过程待聚合」待办。
     *
     * <p>它把「父过程还需要重新聚合」从调用方的自觉变成数据事实：即使某条路径漏掉同步聚合调用、
     * 或同步聚合被规则表临时挡住，待办仍在，秒级消费者会按父过程最新状态补上。
     * 去重键含 triggerAction，因此批量送出 78 条任务也只产生一行待办。</p>
     */
    private void enqueueOrderAggregation(Long orderId, String triggerAction) {
        if (orderId == null) {
            return;
        }
        pendingTaskService.enqueue(StateTransitions.SCENE_ORDER, "order_info", orderId, null, triggerAction);
    }

    // ==================== 不变量修复（由过程体检任务调用） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean repairRecordSigned(Long recordId) {
        DeliveryRecord record = deliveryRecordMapper.selectById(recordId);
        if (record == null || Integer.valueOf(1).equals(record.getSignStatus())) {
            return false; // 已不存在或已签收（并发已被修复）
        }
        // 修复也必须走统一迁移出口：既能留痕，又不至于成为绕过可靠性层的旁路
        return processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_DELIVERY_RECORD)
                        .action(StateTransitions.ACTION_SIGN)
                        .sceneText("配送记录")
                        .entityType("delivery_record")
                        .entityId(recordId)
                        .fromStatus(record.getSignStatus())
                        .toStatus(1)
                        .ruleGoverned(false)
                        .operator("system")
                        .remark("不变量体检修复：任务已完成但签收记录未签收")
                        .build(),
                () -> deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getId, recordId)
                        .eq(DeliveryRecord::getSignStatus, record.getSignStatus())
                        .set(DeliveryRecord::getSignStatus, 1)
                        .set(DeliveryRecord::getSignTime, LocalDateTime.now())
                        .set(DeliveryRecord::getSignPerson, "system(体检修复)")) > 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean repairIntakeForRecord(Long recordId) {
        DeliveryRecord record = deliveryRecordMapper.selectById(recordId);
        if (record == null || !Integer.valueOf(1).equals(record.getSignStatus())) {
            return false; // 仅已签收记录需要营养摄入
        }
        Long exists = nutritionIntakeMapper.selectCount(new LambdaQueryWrapper<NutritionIntake>()
                .eq(NutritionIntake::getDeliveryRecordId, recordId));
        if (exists != null && exists > 0) {
            return false; // 已存在（并发已被修复）
        }
        DeliveryTask task = getTask(record.getTaskId());
        // 与正常签收同一段生成逻辑；唯一键 uk_delivery_record 仲裁并发重复补写
        return idempotencyGuard.insertIgnoringDuplicate(() -> generateNutritionIntake(record, task));
    }

    // ==================== 内部工具 ====================

    private DeliveryTask getTask(Long id) {
        DeliveryTask task = getById(id);
        if (task == null) {
            throw new BusinessException("配送任务不存在");
        }
        return task;
    }

    /**
     * 签收后生成营养摄入记录：按 nutrition_info 每100ml含量 × 实际ml数计算
     */
    private void generateNutritionIntake(DeliveryRecord record, DeliveryTask task) {
        NutritionInfo info = nutritionInfoMapper.selectOne(
                new LambdaQueryWrapper<NutritionInfo>().eq(NutritionInfo::getProductId, record.getProductId()));
        Product product = productMapper.selectById(record.getProductId());
        int mlPerBottle = parseMl(product == null ? null : product.getSpec());
        int totalMl = mlPerBottle * record.getQuantity();

        NutritionIntake intake = new NutritionIntake();
        intake.setStudentId(record.getStudentId());
        intake.setIntakeDate(task.getDeliveryDate());
        intake.setProductId(record.getProductId());
        intake.setQuantity(totalMl);
        intake.setDeliveryRecordId(record.getId());
        if (info != null) {
            BigDecimal factor = BigDecimal.valueOf(totalMl).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            intake.setEnergy(info.getEnergy() == null ? null : info.getEnergy().multiply(factor).setScale(2, RoundingMode.HALF_UP));
            intake.setProtein(info.getProtein() == null ? null : info.getProtein().multiply(factor).setScale(2, RoundingMode.HALF_UP));
            intake.setFat(info.getFat() == null ? null : info.getFat().multiply(factor).setScale(2, RoundingMode.HALF_UP));
            intake.setCalcium(info.getCalcium() == null ? null : info.getCalcium().multiply(factor).setScale(2, RoundingMode.HALF_UP));
        }
        nutritionIntakeMapper.insert(intake);
    }

    /** 从规格字符串解析 ml 数，如 "250ml" → 250，解析失败默认 250 */
    private int parseMl(String spec) {
        if (!StringUtils.hasText(spec)) {
            return 250;
        }
        Matcher m = ML_PATTERN.matcher(spec);
        if (m.find()) {
            return (int) Double.parseDouble(m.group(1));
        }
        return 250;
    }

    private String taskStatusText(Integer status) {
        Map<Integer, String> map = new HashMap<>();
        map.put(1, "待配送");
        map.put(2, "配送中");
        map.put(3, "已完成");
        map.put(4, "已取消");
        return map.getOrDefault(status, "未知");
    }

    private String signStatusText(Integer status) {
        Map<Integer, String> map = new HashMap<>();
        map.put(1, "已签收");
        map.put(2, "未签收");
        map.put(3, "拒收");
        return map.getOrDefault(status, "未知");
    }

    private List<DeliveryTaskVO> convertTasks(List<DeliveryTask> tasks) {
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> classIds = tasks.stream().map(DeliveryTask::getClassId).collect(Collectors.toSet());
        Set<Long> studentIds = tasks.stream().map(DeliveryTask::getStudentId).collect(Collectors.toSet());
        Set<Long> productIds = tasks.stream().map(DeliveryTask::getProductId).collect(Collectors.toSet());
        Set<Long> orderIds = tasks.stream().map(DeliveryTask::getOrderId).collect(Collectors.toSet());

        Map<Long, ClassInfo> classMap = classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));
        Map<Long, Student> studentMap = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, OrderInfo> orderMap = orderInfoMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(OrderInfo::getId, Function.identity()));

        return tasks.stream().map(t -> {
            ClassInfo c = classMap.get(t.getClassId());
            Student s = studentMap.get(t.getStudentId());
            Product p = productMap.get(t.getProductId());
            OrderInfo o = orderMap.get(t.getOrderId());
            return DeliveryTaskVO.from(t,
                    c == null ? null : c.getClassName(),
                    o == null ? null : o.getOrderNo(),
                    s == null ? null : s.getStudentName(),
                    p == null ? null : p.getProductName(),
                    p == null ? null : p.getSpec(),
                    taskStatusText(t.getStatus()));
        }).collect(Collectors.toList());
    }

    private List<DeliveryRecordVO> convertRecords(List<DeliveryRecord> records) {
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> taskIds = records.stream().map(DeliveryRecord::getTaskId).collect(Collectors.toSet());
        Set<Long> studentIds = records.stream().map(DeliveryRecord::getStudentId).collect(Collectors.toSet());
        Set<Long> productIds = records.stream().map(DeliveryRecord::getProductId).collect(Collectors.toSet());

        Map<Long, DeliveryTask> taskMap = baseMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(DeliveryTask::getId, Function.identity()));
        Set<Long> classIds = taskMap.values().stream().map(DeliveryTask::getClassId).collect(Collectors.toSet());
        Map<Long, ClassInfo> classMap = classIds.isEmpty() ? Collections.emptyMap()
                : classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));
        Map<Long, Student> studentMap = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return records.stream().map(r -> {
            DeliveryTask t = taskMap.get(r.getTaskId());
            ClassInfo c = t == null ? null : classMap.get(t.getClassId());
            Student s = studentMap.get(r.getStudentId());
            Product p = productMap.get(r.getProductId());
            return DeliveryRecordVO.from(r,
                    t == null ? null : t.getTaskNo(),
                    t == null ? null : t.getDeliveryDate(),
                    t == null ? null : t.getClassId(),
                    c == null ? null : c.getClassName(),
                    s == null ? null : s.getStudentName(),
                    p == null ? null : p.getProductName(),
                    p == null ? null : p.getSpec(),
                    signStatusText(r.getSignStatus()));
        }).collect(Collectors.toList());
    }
}
