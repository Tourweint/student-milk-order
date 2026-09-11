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
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
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

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern ML_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ml", Pattern.CASE_INSENSITIVE);

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
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
            for (OrderItem item : items) {
                // 幂等：同日期+订单+奶品已生成则跳过
                Long exists = baseMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getDeliveryDate, date)
                        .eq(DeliveryTask::getOrderId, order.getId())
                        .eq(DeliveryTask::getProductId, item.getProductId()));
                if (exists != null && exists > 0) {
                    continue;
                }
                // 创建任务
                DeliveryTask task = new DeliveryTask();
                task.setTaskNo("DT" + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 9999));
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

                count++;
            }
        }
        return count;
    }

    // ==================== 任务查询 ====================

    @Override
    public IPage<DeliveryTaskVO> pageTasks(Long pageNum, Long pageSize, String deliveryDate, Long classId, Integer status) {
        Page<DeliveryTask> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<DeliveryTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(deliveryDate), DeliveryTask::getDeliveryDate, deliveryDate)
                .eq(classId != null, DeliveryTask::getClassId, classId)
                .eq(status != null, DeliveryTask::getStatus, status)
                .orderByDesc(DeliveryTask::getId);
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
    public void startDelivery(Long taskId) {
        DeliveryTask task = getTask(taskId);
        if (task.getStatus() != 1) {
            throw new BusinessException("仅待配送任务可开始配送");
        }
        task.setStatus(2); // 配送中
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
        // 1. 更新签收记录
        record.setSignStatus(1); // 已签收
        record.setSignTime(LocalDateTime.now());
        record.setSignPerson(StringUtils.hasText(request.getSignPerson()) ? request.getSignPerson() : SecurityUtils.getCurrentUsername());
        if (StringUtils.hasText(request.getRemark())) {
            record.setRemark(request.getRemark());
        }
        deliveryRecordMapper.updateById(record);

        // 2. 更新任务状态为已完成
        DeliveryTask task = getTask(record.getTaskId());
        task.setStatus(3); // 已完成
        updateById(task);

        // 3. 生成营养摄入记录
        generateNutritionIntake(record, task);
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
        record.setSignStatus(3); // 拒收
        record.setSignTime(LocalDateTime.now());
        record.setRemark(StringUtils.hasText(reason) ? reason : "拒收");
        deliveryRecordMapper.updateById(record);

        // 任务改为已取消
        DeliveryTask task = getTask(record.getTaskId());
        task.setStatus(4);
        updateById(task);
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
