package com.milk.order.module.delivery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.dto.UndeliveredReportRequest;
import com.milk.order.module.delivery.entity.DeliveryTask;
import com.milk.order.module.delivery.entity.DeliveryUndeliveredReport;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.delivery.mapper.DeliveryUndeliveredReportMapper;
import com.milk.order.module.delivery.service.DeliveryUndeliveredReportService;
import com.milk.order.module.delivery.vo.UndeliveredReportVO;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 当日未送达申报服务实现
 *
 * <p>关键判断：**申报必须针对「配送中」的任务**。理由是这个标记只对"兜底会误签"这一类记录有意义——
 * 兜底候选集本身就是「任务配送中 + 配送日 &lt; 今天 + 记录未签收」；待配送(1) 的任务不在候选集里
 * （不会产生虚假签收），已完成/已取消(3/4) 的任务已是终态（兜底不会碰）。
 * 因此把申报范围限定在「配送中」既精确又不越权，避免申报变成"随便给任务打个标签"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryUndeliveredReportServiceImpl
        extends ServiceImpl<DeliveryUndeliveredReportMapper, DeliveryUndeliveredReport>
        implements DeliveryUndeliveredReportService {

    /** 任务状态：配送中（已送出、未签收） */
    private static final int TASK_DISPATCHING = 2;

    /** 跟进状态：待跟进 / 已跟进 */
    private static final int HANDLE_PENDING = 0;
    private static final int HANDLE_DONE = 1;

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final ProductMapper productMapper;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyGuard idempotencyGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void report(UndeliveredReportRequest request) {
        DeliveryTask task = deliveryTaskMapper.selectById(request.getTaskId());
        if (task == null) {
            throw new BusinessException("配送任务不存在");
        }
        if (task.getStatus() == null || task.getStatus() != TASK_DISPATCHING) {
            throw new BusinessException("仅「配送中」（已送出、未签收）的任务可申报未送达，当前任务状态不允许申报");
        }
        if (task.getDeliveryDate() == null || task.getDeliveryDate().isAfter(LocalDate.now())) {
            throw new BusinessException("不能申报尚未到来的配送日");
        }
        DeliveryUndeliveredReport report = new DeliveryUndeliveredReport();
        report.setTaskId(task.getId());
        report.setOrderId(task.getOrderId());
        report.setProductId(task.getProductId());
        report.setClassId(task.getClassId());
        report.setDeliveryDate(task.getDeliveryDate());
        report.setReason(request.getReason().trim());
        report.setReportBy(SecurityUtils.getCurrentUsername());
        report.setHandleStatus(HANDLE_PENDING);
        // 一条任务只允许一条申报：唯一键 uk_task 仲裁，"先查再插"在并发下会重复
        if (!idempotencyGuard.insertIgnoringDuplicate(() -> save(report))) {
            throw new BusinessException("该任务已申报过未送达，无需重复申报");
        }
        log.warn("[未送达申报] 任务 {}（订单 {}，配送日 {}）由 {} 申报未送达：{}；已排除出自动签收候选集",
                task.getTaskNo(), task.getOrderId(), task.getDeliveryDate(), report.getReportBy(), report.getReason());
    }

    @Override
    public IPage<UndeliveredReportVO> pageReports(Long pageNum, Long pageSize, String deliveryDate,
                                                  Integer handleStatus, Long classId) {
        // 数据权限：班主任仅能查看本班（与配送任务/配送记录查询同一口径）
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getClassId() != null) {
            classId = scope.getClassId();
        }
        Page<DeliveryUndeliveredReport> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<DeliveryUndeliveredReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(deliveryDate), DeliveryUndeliveredReport::getDeliveryDate, deliveryDate)
                .eq(handleStatus != null, DeliveryUndeliveredReport::getHandleStatus, handleStatus)
                .eq(classId != null, DeliveryUndeliveredReport::getClassId, classId)
                .orderByDesc(DeliveryUndeliveredReport::getId);
        IPage<DeliveryUndeliveredReport> reportPage = page(page, wrapper);

        IPage<UndeliveredReportVO> result = new Page<>(reportPage.getCurrent(), reportPage.getSize(), reportPage.getTotal());
        result.setRecords(convert(reportPage.getRecords()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, String remark) {
        DeliveryUndeliveredReport report = getById(id);
        if (report == null) {
            throw new BusinessException("申报记录不存在");
        }
        // 条件更新 + 幂等：已是「已跟进」直接返回，重复点击不覆盖首次跟进人与时间
        boolean updated = lambdaUpdate()
                .eq(DeliveryUndeliveredReport::getId, id)
                .eq(DeliveryUndeliveredReport::getHandleStatus, HANDLE_PENDING)
                .set(DeliveryUndeliveredReport::getHandleStatus, HANDLE_DONE)
                .set(DeliveryUndeliveredReport::getHandleRemark, remark)
                .set(DeliveryUndeliveredReport::getHandleBy, SecurityUtils.getCurrentUsername())
                .set(DeliveryUndeliveredReport::getHandleTime, LocalDateTime.now())
                .update();
        if (updated) {
            log.info("[未送达申报] #{}（任务 {}）标记为已跟进，说明：{}；任务状态未被本操作改变，实际处置走签收/拒收/取消出口",
                    id, report.getTaskId(), remark);
        }
    }

    /** 列表回显：任务号/数量、订单号、学生/班级、奶品名 */
    private List<UndeliveredReportVO> convert(List<DeliveryUndeliveredReport> reports) {
        if (reports.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, DeliveryTask> taskMap = deliveryTaskMapper.selectBatchIds(
                        reports.stream().map(DeliveryUndeliveredReport::getTaskId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DeliveryTask::getId, Function.identity()));
        Set<Long> orderIds = reports.stream().map(DeliveryUndeliveredReport::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orderNos = orderIds.isEmpty() ? Collections.emptyMap()
                : orderInfoMapper.selectBatchIds(orderIds).stream()
                        .collect(Collectors.toMap(OrderInfo::getId, OrderInfo::getOrderNo));
        Set<Long> studentIds = taskMap.values().stream().map(DeliveryTask::getStudentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Student> studentMap = studentIds.isEmpty() ? Collections.emptyMap()
                : studentMapper.selectBatchIds(studentIds).stream()
                        .collect(Collectors.toMap(Student::getId, Function.identity()));
        Set<Long> classIds = reports.stream().map(DeliveryUndeliveredReport::getClassId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> classNames = classIds.isEmpty() ? Collections.emptyMap()
                : classInfoMapper.selectBatchIds(classIds).stream()
                        .collect(Collectors.toMap(ClassInfo::getId, ClassInfo::getClassName));
        Set<Long> productIds = reports.stream().map(DeliveryUndeliveredReport::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> productNames = productIds.isEmpty() ? Collections.emptyMap()
                : productMapper.selectBatchIds(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Product::getProductName));

        return reports.stream().map(r -> {
            UndeliveredReportVO vo = new UndeliveredReportVO();
            vo.setId(r.getId());
            vo.setTaskId(r.getTaskId());
            vo.setOrderId(r.getOrderId());
            vo.setOrderNo(orderNos.get(r.getOrderId()));
            vo.setDeliveryDate(r.getDeliveryDate());
            vo.setClassName(classNames.get(r.getClassId()));
            vo.setProductName(productNames.get(r.getProductId()));
            vo.setReason(r.getReason());
            vo.setReportBy(r.getReportBy());
            vo.setReportTime(r.getCreateTime());
            vo.setHandleStatus(r.getHandleStatus());
            vo.setHandleRemark(r.getHandleRemark());
            vo.setHandleBy(r.getHandleBy());
            vo.setHandleTime(r.getHandleTime());
            DeliveryTask task = taskMap.get(r.getTaskId());
            if (task != null) {
                vo.setTaskNo(task.getTaskNo());
                vo.setQuantity(task.getQuantity());
                vo.setTaskStatus(task.getStatus());
                Student student = studentMap.get(task.getStudentId());
                vo.setStudentName(student == null ? null : student.getStudentName());
            }
            return vo;
        }).collect(Collectors.toList());
    }
}
