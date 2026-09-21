package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.entity.DeliveryRecord;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.mapper.DeliveryRecordMapper;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.product.entity.DailyQuota;
import com.milk.order.module.product.entity.DailyQuotaUsage;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductBatch;
import com.milk.order.module.product.mapper.DailyQuotaMapper;
import com.milk.order.module.product.mapper.DailyQuotaUsageMapper;
import com.milk.order.module.product.mapper.ProductBatchMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.ProductBatchService;
import com.milk.order.module.product.vo.BatchTraceVO;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 奶品批次服务实现（批次追溯钩子）
 *
 * <p>只读反查 + 批次档案维护，**不触碰扣减/结转/台账/状态机**：
 * 批号是配额池上的一个可选字符串标注，因此召回反查的链路是
 * 「批号 → 标注过的配额池 (日期×品种) → 该池的扣减台账 (daily_quota_usage) → 订单 → 学生/班级 → 配送任务/签收记录」。</p>
 *
 * <p>两个诚实边界（写进文档，避免误读为"全量批次库存"）：
 * ① 学期套餐不经配额池，因此反查只覆盖单日零散订购；
 * ② 结转使「池子日期」可能早于「配送日」（9/1 的池子被 9/2 的订单扣走），
 * 所以结果里两者分开呈现，配送日取订单自身区间。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBatchServiceImpl extends ServiceImpl<ProductBatchMapper, ProductBatch> implements ProductBatchService {

    private final DailyQuotaMapper dailyQuotaMapper;
    private final DailyQuotaUsageMapper dailyQuotaUsageMapper;
    private final ProductMapper productMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryRecordMapper deliveryRecordMapper;
    private final IdempotencyGuard idempotencyGuard;

    @Override
    public List<ProductBatch> listBatches(Long productId, Integer status) {
        LambdaQueryWrapper<ProductBatch> wrapper = new LambdaQueryWrapper<>();
        if (productId != null) {
            wrapper.eq(ProductBatch::getProductId, productId);
        }
        if (status != null) {
            wrapper.eq(ProductBatch::getStatus, status);
        }
        wrapper.orderByDesc(ProductBatch::getArrivalDate).orderByDesc(ProductBatch::getId);
        return list(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createBatch(ProductBatch batch) {
        String batchNo = normalizeBatchNo(batch.getBatchNo());
        requireProduct(batch.getProductId());
        if (batch.getStatus() == null) {
            batch.setStatus(1);
        }
        batch.setId(null);
        batch.setBatchNo(batchNo);
        // 批号唯一由数据库唯一键仲裁（uk_batch_no），"先查再插"在并发下会重复建档
        if (!idempotencyGuard.insertIgnoringDuplicate(() -> save(batch))) {
            throw new BusinessException("批号已存在：" + batchNo);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBatch(ProductBatch batch) {
        if (batch.getId() == null) {
            throw new BusinessException("批次ID不能为空");
        }
        if (getById(batch.getId()) == null) {
            throw new BusinessException("批次不存在");
        }
        batch.setBatchNo(normalizeBatchNo(batch.getBatchNo()));
        requireProduct(batch.getProductId());
        try {
            updateById(batch);
        } catch (Exception e) {
            if (idempotencyGuard.isDuplicate(e)) {
                throw new BusinessException("批号已存在：" + batch.getBatchNo());
            }
            throw e;
        }
    }

    @Override
    public void deleteBatch(Long id) {
        if (id == null || getById(id) == null) {
            throw new BusinessException("批次不存在");
        }
        // 逻辑删除只下线档案；已标注的配额池保留批号字符串，召回反查不受影响（反查以池子标注为准）
        removeById(id);
    }

    @Override
    public BatchTraceVO trace(String batchNo) {
        String no = normalizeBatchNo(batchNo);
        // 1. 批号 → 标注过该批号的配额池（日期×品种）
        List<DailyQuota> pools = dailyQuotaMapper.selectList(new LambdaQueryWrapper<DailyQuota>()
                .eq(DailyQuota::getBatchNo, no)
                .orderByAsc(DailyQuota::getQuotaDate));
        BatchTraceVO vo = new BatchTraceVO();
        vo.setBatch(getOne(new LambdaQueryWrapper<ProductBatch>().eq(ProductBatch::getBatchNo, no), false));
        vo.setPoolCount(pools.size());
        if (pools.isEmpty()) {
            vo.setPools(Collections.emptyList());
            vo.setDeliveries(Collections.emptyList());
            vo.setTotalBoxes(0);
            return vo;
        }
        Map<Long, String> productNames = productNames(pools.stream()
                .map(DailyQuota::getProductId).collect(Collectors.toSet()));
        vo.setPools(pools.stream().map(p -> {
            BatchTraceVO.PoolRow row = new BatchTraceVO.PoolRow();
            row.setQuotaDate(p.getQuotaDate());
            row.setProductId(p.getProductId());
            row.setProductName(productNames.get(p.getProductId()));
            row.setTotalQuota(p.getTotalQuota());
            row.setUsedQuota(p.getUsedQuota());
            row.setRemark(p.getRemark());
            return row;
        }).collect(Collectors.toList()));

        // 2. 池子 → 扣减台账。先用 (日期 in, 品种 in) 取候选，再按「池子键」在内存里精确过滤：
        //    直接写 (日期,品种) 的 OR 组合容易写成"笛卡尔式"误命中（9/1 的池子不该命中 9/1 另一品种）
        Set<String> poolKeys = pools.stream()
                .map(p -> poolKey(p.getQuotaDate(), p.getProductId())).collect(Collectors.toSet());
        List<DailyQuotaUsage> usages = dailyQuotaUsageMapper.selectList(new LambdaQueryWrapper<DailyQuotaUsage>()
                        .in(DailyQuotaUsage::getQuotaDate, pools.stream().map(DailyQuota::getQuotaDate).distinct().toList())
                        .in(DailyQuotaUsage::getProductId, pools.stream().map(DailyQuota::getProductId).distinct().toList()))
                .stream()
                .filter(u -> poolKeys.contains(poolKey(u.getQuotaDate(), u.getProductId())))
                .collect(Collectors.toList());
        vo.setTotalBoxes(usages.stream().mapToInt(u -> u.getBoxes() == null ? 0 : u.getBoxes()).sum());
        if (usages.isEmpty()) {
            vo.setDeliveries(Collections.emptyList());
            return vo;
        }

        // 3. 台账 → 订单 → 学生/班级
        Map<Long, List<DailyQuotaUsage>> usagesByOrder = usages.stream()
                .collect(Collectors.groupingBy(DailyQuotaUsage::getOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, OrderInfo> orderMap = orderInfoMapper.selectBatchIds(usagesByOrder.keySet()).stream()
                .collect(Collectors.toMap(OrderInfo::getId, Function.identity()));
        Set<Long> studentIds = orderMap.values().stream()
                .map(OrderInfo::getStudentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Student> studentMap = studentIds.isEmpty() ? Collections.emptyMap()
                : studentMapper.selectBatchIds(studentIds).stream()
                        .collect(Collectors.toMap(Student::getId, Function.identity()));
        Set<Long> classIds = studentMap.values().stream()
                .map(Student::getClassId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> classNames = classIds.isEmpty() ? Collections.emptyMap()
                : classInfoMapper.selectBatchIds(classIds).stream()
                        .collect(Collectors.toMap(ClassInfo::getId, ClassInfo::getClassName));

        // 4. 配送任务与签收记录（按 订单×品种×配送日 精确取；任务可能被平移/退订作废，取不到即为空）
        Set<Long> orderIds = usagesByOrder.keySet();
        Set<Long> productIds = pools.stream().map(DailyQuota::getProductId).collect(Collectors.toSet());
        Map<String, DeliveryTask> taskMap = deliveryTaskMapper.selectList(new LambdaQueryWrapper<DeliveryTask>()
                        .in(DeliveryTask::getOrderId, orderIds)
                        .in(DeliveryTask::getProductId, productIds)).stream()
                .collect(Collectors.toMap(
                        t -> taskKey(t.getOrderId(), t.getProductId(), t.getDeliveryDate()),
                        Function.identity(), (a, b) -> a));
        Map<Long, Integer> signStatusByTask = taskMap.isEmpty() ? Collections.emptyMap()
                : deliveryRecordMapper.selectList(new LambdaQueryWrapper<DeliveryRecord>()
                                .in(DeliveryRecord::getTaskId, taskMap.values().stream()
                                        .map(DeliveryTask::getId).collect(Collectors.toSet()))).stream()
                        .collect(Collectors.toMap(DeliveryRecord::getTaskId, DeliveryRecord::getSignStatus, (a, b) -> a));

        List<BatchTraceVO.DeliveryRow> rows = new ArrayList<>();
        for (Map.Entry<Long, List<DailyQuotaUsage>> entry : usagesByOrder.entrySet()) {
            OrderInfo order = orderMap.get(entry.getKey());
            LocalDate deliveryDate = order == null ? null : order.getDeliveryStartDate();
            Student student = order == null ? null : studentMap.get(order.getStudentId());
            for (DailyQuotaUsage usage : entry.getValue()) {
                BatchTraceVO.DeliveryRow row = new BatchTraceVO.DeliveryRow();
                row.setQuotaDate(usage.getQuotaDate());
                row.setOrderId(entry.getKey());
                row.setOrderNo(order == null ? null : order.getOrderNo());
                row.setStudentId(order == null ? null : order.getStudentId());
                row.setStudentName(student == null ? null : student.getStudentName());
                row.setClassName(student == null ? null : classNames.get(student.getClassId()));
                row.setProductId(usage.getProductId());
                row.setProductName(productNames.get(usage.getProductId()));
                row.setBoxes(usage.getBoxes());
                row.setDeliveryDate(deliveryDate);
                DeliveryTask task = deliveryDate == null ? null
                        : taskMap.get(taskKey(entry.getKey(), usage.getProductId(), deliveryDate));
                if (task != null) {
                    row.setTaskNo(task.getTaskNo());
                    row.setTaskStatus(task.getStatus());
                    row.setSignStatus(signStatusByTask.get(task.getId()));
                }
                rows.add(row);
            }
        }
        // 召回工作清单：按配送日倒序（越近越需要拦截），同日按订单升序保证可复现
        rows.sort(Comparator.comparing(BatchTraceVO.DeliveryRow::getDeliveryDate,
                        Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(BatchTraceVO.DeliveryRow::getOrderId));
        vo.setDeliveries(rows);
        log.info("[批次反查] 批号 {} 关联池子 {} 个，命中台账 {} 条、合计 {} 盒",
                no, pools.size(), usages.size(), vo.getTotalBoxes());
        return vo;
    }

    private String normalizeBatchNo(String batchNo) {
        if (!StringUtils.hasText(batchNo)) {
            throw new BusinessException("批号不能为空");
        }
        return batchNo.trim();
    }

    private void requireProduct(Long productId) {
        if (productId == null || productMapper.selectById(productId) == null) {
            throw new BusinessException("请选择有效的奶品");
        }
    }

    private Map<Long, String> productNames(Set<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductName));
    }

    private String poolKey(LocalDate quotaDate, Long productId) {
        return quotaDate + "#" + productId;
    }

    private String taskKey(Long orderId, Long productId, LocalDate deliveryDate) {
        return orderId + "#" + productId + "#" + deliveryDate;
    }
}
