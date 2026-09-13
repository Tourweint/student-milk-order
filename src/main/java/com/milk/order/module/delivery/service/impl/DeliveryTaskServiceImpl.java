package com.milk.order.module.delivery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.dto.SignRequest;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.mapper.DeliveryRecordMapper;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.DailyDispatchSummaryVO;
import com.milk.order.module.delivery.vo.DeliveryRecordVO;
import com.milk.order.module.delivery.vo.DeliveryTaskVO;
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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * 任务开始配送联动订单状态（已支付→配送中）须经 OrderInfoService 统一状态机出口；
     * OrderInfoServiceImpl 反向依赖本服务，@Lazy 注入打破构造器循环依赖
     */
    @Lazy
    @Autowired
    private OrderInfoService orderInfoService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern ML_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ml", Pattern.CASE_INSENSITIVE);

    /** 任务号单调序列：整期任务在同一事务的同一秒内批量生成，纯随机后缀必撞 uk_task_no 唯一键 */
    private static final AtomicLong TASK_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private String nextTaskNo() {
        return "DT" + LocalDateTime.now().format(NO_FMT)
                + String.format("%06d", TASK_SEQ.incrementAndGet() % 1000000);
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
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        if (items.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (OrderItem item : items) {
                count += createTaskIfAbsent(order, item, date);
            }
        }
        return count;
    }

    /** 为单个订单展开某一日期的任务（generateTasks 复用） */
    private int generateTasksForOrderDate(OrderInfo order, LocalDate date) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        int count = 0;
        for (OrderItem item : items) {
            count += createTaskIfAbsent(order, item, date);
        }
        return count;
    }

    /**
     * 创建某订单某奶品某日期的任务与签收记录；幂等：同日期+订单+奶品已生成则跳过
     */
    private int createTaskIfAbsent(OrderInfo order, OrderItem item, LocalDate date) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getDeliveryDate, date)
                .eq(DeliveryTask::getOrderId, order.getId())
                .eq(DeliveryTask::getProductId, item.getProductId()));
        if (exists != null && exists > 0) {
            return 0;
        }
        DeliveryTask task = new DeliveryTask();
        task.setTaskNo(nextTaskNo());
        task.setDeliveryDate(date);
        task.setClassId(order.getClassId());
        task.setOrderId(order.getId());
        task.setStudentId(order.getStudentId());
        task.setProductId(item.getProductId());
        task.setQuantity(item.getQuantity());
        task.setStatus(1); // 待配送
        baseMapper.insert(task);

        // 创建签收记录
        DeliveryRecord record = new DeliveryRecord();
        record.setTaskId(task.getId());
        record.setStudentId(order.getStudentId());
        record.setProductId(item.getProductId());
        record.setQuantity(item.getQuantity());
        record.setSignStatus(2); // 未签收
        deliveryRecordMapper.insert(record);
        return 1;
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
        if (task.getStatus() != 1) {
            throw new BusinessException("仅待配送任务可开始配送");
        }
        doDispatch(task);
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
        Set<Long> orderIds = new LinkedHashSet<>();
        for (DeliveryTask task : tasks) {
            doDispatch(task);
            orderIds.add(task.getOrderId());
        }
        // 退款闸门：任务开始配送即联动订单已支付→配送中，此后订单不可自助退订
        for (Long orderId : orderIds) {
            orderInfoService.markDeliveringIfPaid(orderId);
        }
        return tasks.size();
    }

    /** 单条任务开始配送：状态待配送→配送中，记录派送人与派送时间（审计痕迹） */
    private void doDispatch(DeliveryTask task) {
        task.setStatus(2); // 配送中
        task.setDispatchBy(SecurityUtils.getCurrentUsername());
        task.setDispatchTime(LocalDateTime.now());
        updateById(task);
    }

    @Override
    public void cancelTask(Long taskId, String reason) {
        DeliveryTask task = getTask(taskId);
        if (task.getStatus() != 1 && task.getStatus() != 2) {
            throw new BusinessException("仅待配送/配送中任务可取消");
        }
        task.setStatus(4); // 已取消
        task.setRemark(StringUtils.hasText(reason) ? reason : task.getRemark());
        updateById(task);
        // 同步取消关联签收记录
        DeliveryRecord record = deliveryRecordMapper.selectOne(
                new LambdaQueryWrapper<DeliveryRecord>().eq(DeliveryRecord::getTaskId, taskId));
        if (record != null && record.getSignStatus() == 2) {
            record.setSignStatus(3); // 拒收
            record.setRemark("任务已取消");
            deliveryRecordMapper.updateById(record);
        }
        // 任务全部到达终态时自动完成订单
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
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
        record.setSignStatus(1); // 已签收
        record.setSignTime(LocalDateTime.now());
        record.setSignPerson(signPerson);
        if (StringUtils.hasText(remark)) {
            record.setRemark(remark);
        }
        deliveryRecordMapper.updateById(record);

        // 任务状态为已完成
        task.setStatus(3); // 已完成
        updateById(task);

        // 生成营养摄入记录
        generateNutritionIntake(record, task);

        // 任务全部到达终态时自动完成订单
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
    }

    @Override
    public void rejectRecord(Long recordId, String reason) {
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
        record.setSignStatus(3); // 拒收
        record.setSignTime(LocalDateTime.now());
        record.setRemark(StringUtils.hasText(reason) ? reason : "拒收");
        deliveryRecordMapper.updateById(record);

        // 任务改为已取消
        task.setStatus(4);
        updateById(task);

        // 任务全部到达终态时自动完成订单
        orderInfoService.completeOrderIfAllTasksDone(task.getOrderId());
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
            task.setStatus(4); // 已取消
            task.setRemark("订单已退订，任务作废");
            updateById(task);
            // 同步取消未签收的签收记录
            DeliveryRecord record = deliveryRecordMapper.selectOne(
                    new LambdaQueryWrapper<DeliveryRecord>().eq(DeliveryRecord::getTaskId, task.getId()));
            if (record != null && record.getSignStatus() == 2) {
                record.setSignStatus(3); // 拒收
                record.setRemark("订单已退订");
                deliveryRecordMapper.updateById(record);
            }
            count++;
        }
        return count;
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
