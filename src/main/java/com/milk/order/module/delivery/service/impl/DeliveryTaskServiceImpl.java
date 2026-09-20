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
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.mapper.DeliveryCompensationMapper;
import com.milk.order.module.delivery.mapper.DeliveryRecordMapper;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
import com.milk.order.module.delivery.vo.ParentHomeVO;
import com.milk.order.module.delivery.vo.PendingSignVO;
import com.milk.order.module.delivery.vo.ShiftResultVO;

import com.milk.order.module.product.service.DailyQuotaService;
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
    private final DeliveryCompensationMapper deliveryCompensationMapper;

    /**
     * 任务开始配送联动订单状态（已支付→配送中）须经 OrderInfoService 统一状态机出口；
     * OrderInfoServiceImpl 反向依赖本服务，@Lazy 注入打破构造器循环依赖
     */
    @Lazy
    @Autowired
    private OrderInfoService orderInfoService;

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern ML_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ml", Pattern.CASE_INSENSITIVE);

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
        int count = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            count += createTasksForDate(order, date, demandByProduct);
        }
        return count;
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
        String cancelReason = StringUtils.hasText(reason) ? reason : task.getRemark();
        // 过程层严格迁移：规则表白名单（待配送/配送中可取消，已完成/已取消禁止）替代原先硬编码判断；
        // CAS 以读取时的来源状态为条件，与并发签收/开始配送竞争，仅一方生效——
        // 否则「读状态→判断→全量更新」会让已完成(3)的任务被并发取消回退为已取消(4)
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
            boolean cancelled = processTransitionExecutor.attempt(
                    TransitionSpec.builder()
                            .scene(StateTransitions.SCENE_DELIVERY_TASK)
                            .action(StateTransitions.ACTION_TASK_CANCEL)
                            .sceneText("配送任务")
                            .entityType("delivery_task")
                            .entityId(t.getId())
                            .bizNo(t.getTaskNo())
                            .fromStatus(1)
                            .toStatus(4)
                            .remark("配送日平移至 " + target)
                            .build(),
                    () -> lambdaUpdate()
                            .eq(DeliveryTask::getId, t.getId())
                            .eq(DeliveryTask::getStatus, 1)
                            .set(DeliveryTask::getStatus, 4)
                            .set(DeliveryTask::getRemark, "配送日平移至 " + target)
                            .update());
            if (!cancelled) {
                result.setSkipped(result.getSkipped() + 1);
                result.getMessages().add(t.getTaskNo() + " 已被并发处理，跳过");
                continue;
            }
            // 原记录不置拒收（平移不是拒收），仅标注 remark，保持未签收
            deliveryRecordMapper.update(null, new LambdaUpdateWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getTaskId, t.getId())
                    .eq(DeliveryRecord::getSignStatus, 2)
                    .set(DeliveryRecord::getRemark, "已平移至 " + target));

            int qty = t.getQuantity() == null ? 1 : t.getQuantity();
            DeliveryTask exist = targetMap.get(t.getOrderId() + "#" + t.getProductId());
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
                result.setMerged(result.getMerged() + 1);
            } else {
                OrderInfo order = orderInfoMapper.selectById(t.getOrderId());
                if (order == null) {
                    throw new BusinessException("订单不存在，无法平移 " + t.getTaskNo());
                }
                DeliveryTask created = createTaskReturning(order, t.getProductId(), qty, target);
                if (created == null) {
                    throw new BusinessException(t.getTaskNo() + " 平移失败：目标日已存在同键任务");
                }
                // 登记进 map：同订单同品种的后续源任务应合并到这条新任务，而不是重复新建
                targetMap.put(t.getOrderId() + "#" + t.getProductId(), created);
                result.setCreated(result.getCreated() + 1);
            }
            result.setShifted(result.getShifted() + 1);
            enqueueOrderAggregation(t.getOrderId(), StateTransitions.ACTION_TASK_CANCEL);
        }
        return result;
    }

    /**
     * 学期末摊平（见方案 §1.6）。
     *
     * <p>可重复执行、幂等：每次都把窗口内待配送任务的数量**重置为基准量**再重新分配，
     * 不在此前结果上叠加。基准量 = 1 + 该任务补送量（从 delivery_compensation 汇总），
     * 因此拒收补送的 +1 不会被抹掉；摊平后量 = min(基准量 + 分配量, 3)。</p>
     *
     * <p>剩余量口径 M = 该订单从今天起 status ∈ {1,2} 的 quantity 合计（**含截止日之后的任务**，
     * 这才是"还剩多少盒"）；K = 截止日当天及之前、仍待配送的任务数。截止日之后的**待配送**任务
     * 在本次操作中作废（其盒数已提前并入窗口，不能重复配送）；已送达与已开始配送(2)的任务不动。
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
        // 需摊平的盒数 = 全部待配送任务的量（含将在窗口外作废的部分）；已送出(2)不可改、不计入分配
        int toDistribute = remaining.stream()
                .filter(t -> t.getStatus() == 1)
                .mapToInt(t -> t.getQuantity() == null ? 0 : t.getQuantity()).sum();
        int k = window.size();
        if (toDistribute > 2 * k) {
            throw new BusinessException("剩余 " + toDistribute + " 盒、截止日前的配送日仅 " + k + " 个，"
                    + "即使每天 2 盒也只能送到 " + (2 * k) + " 盒，请把截止日期延后");
        }
        if (toDistribute < k) {
            throw new BusinessException("剩余 " + toDistribute + " 盒少于截止日前的 " + k + " 个配送日，"
                    + "请缩短截止日期");
        }
        // 补送量：按 target_task_id 汇总（重置基准 = 1 + 补送量，避免抹掉拒收补送）
        List<Long> windowIds = window.stream().map(DeliveryTask::getId).collect(Collectors.toList());
        Map<Long, Integer> compensationMap = new HashMap<>();
        List<DeliveryCompensation> compensations = deliveryCompensationMapper.selectList(
                new LambdaQueryWrapper<DeliveryCompensation>().in(DeliveryCompensation::getTargetTaskId, windowIds));
        for (DeliveryCompensation c : compensations) {
            compensationMap.merge(c.getTargetTaskId(), c.getBoxes() == null ? 0 : c.getBoxes(), Integer::sum);
        }
        // 分配：基础量 floor，余数前几天各多 1 盒（摊平口径每天 ≤2；叠加补送后总 ≤3）
        int base = toDistribute / k;
        int remainder = toDistribute % k;
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
        // 复用人工签收共用流程：记录未签收→已签收（CAS）、任务配送中→已完成、生成营养摄入、订单全终态自动完成
        doSign(record, AUTO_SIGN_PERSON, AUTO_SIGN_REMARK);
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
