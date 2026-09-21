package com.milk.order.module.refund.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.InstanceIdentity;
import com.milk.order.common.constant.StateTransitions;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.OrderStatus;
import com.milk.order.common.enums.RefundStatus;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.delivery.vo.RefundableTaskVO;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.entity.PaymentRecord;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.mapper.OrderItemMapper;
import com.milk.order.module.order.mapper.PaymentRecordMapper;
import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.refund.dto.RefundApplyRequest;
import com.milk.order.module.refund.dto.RefundAuditRequest;
import com.milk.order.module.refund.entity.RefundOrder;
import com.milk.order.module.refund.mapper.RefundOrderMapper;
import com.milk.order.module.refund.service.RefundOrderService;
import com.milk.order.module.refund.vo.RefundOrderVO;
import com.milk.order.module.refund.vo.RefundPreviewVO;
import com.milk.order.module.refund.vo.SettlementItemVO;
import com.milk.order.module.refund.vo.SettlementResultVO;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.mapper.SysUserMapper;
import com.milk.order.module.user.service.DataScopeResolver;
import com.milk.order.process.ProcessTransitionExecutor;
import com.milk.order.process.TransitionSpec;
import com.milk.order.process.pending.ProcessPendingTaskService;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 退款域父过程实现。
 *
 * <p><b>为什么退款侧不注入 OrderInfoService</b>：退订（R4）由 {@code OrderInfoServiceImpl} 反向调用本服务，
 * 若本服务再依赖 OrderInfoService 就会形成 order ↔ refund 环形依赖；因此订单数据一律经
 * {@code OrderInfoMapper}/{@code PaymentRecordMapper} 读取（与 delivery 模块读 order_info 的既有做法一致），
 * 父订单聚合只走 {@code ProcessPendingTaskService.enqueue} 实时通道（由过程层负责收敛）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrderServiceImpl extends ServiceImpl<RefundOrderMapper, RefundOrder> implements RefundOrderService {

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final StudentMapper studentMapper;
    private final SysUserMapper sysUserMapper;
    private final DeliveryTaskService deliveryTaskService;
    private final DailyQuotaService dailyQuotaService;
    private final ProcessTransitionExecutor processTransitionExecutor;
    private final ProcessPendingTaskService pendingTaskService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyGuard idempotencyGuard;

    /**
     * 自代理：毕业清算必须逐单独立事务（单笔失败不阻塞其余），
     * 同类内部直调不经过 Spring 代理，@Transactional 会形同虚设。
     */
    @Lazy
    @Autowired
    private RefundOrderService self;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 退款单号内自增序列（实例内），配合 {@link InstanceIdentity#TAG} 保证跨实例唯一 */
    private static final AtomicLong NO_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private static final int REFUND_CHANNEL_MOCK = 1;

    /** 单号：前缀 + 秒级时间戳 + 实例标识 + 实例内自增序号（与订单号/流水号同规则） */
    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT) + InstanceIdentity.TAG
                + String.format("%06d", NO_SEQ.incrementAndGet() % 1000000);
    }

    // ==================== 申请 / 查询 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long applyRefund(Long orderId, RefundApplyRequest request) {
        OrderInfo order = requireOrder(orderId);
        checkOrderAccess(order);
        if (OrderStatus.CANCELLED.getCode().equals(order.getStatus())) {
            throw new BusinessException("订单已退订，无需申请退款");
        }
        if (OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BusinessException("订单尚未支付，可直接取消订单，无需退款");
        }
        if (deliveryTaskService.listRefundableTasks(orderId).isEmpty()) {
            throw new BusinessException("该订单当前无可退期次（奶已配送或已退款）");
        }
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo(nextNo("RF"));
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setStudentId(order.getStudentId());
        refund.setUserId(currentUserId());
        refund.setApplyBoxCount(request.getApplyBoxCount());
        refund.setRefundedBoxes(0);
        refund.setStatus(RefundStatus.PENDING_AUDIT.getCode());
        refund.setApplyReason(StringUtils.hasText(request.getReason()) ? request.getReason() : "申请按期次退款");
        // 申请幂等（R6）：由生成列 + 唯一索引 uk_refund_active 仲裁，不用"先 selectCount 再 insert"
        // （后者在多实例下会双双查不到、再双双插入）
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(refund));
        if (!inserted) {
            throw new BusinessException("该订单已有进行中的退款单，请勿重复申请");
        }
        log.info("[退款] 订单 {} 提交退款申请，退款单 {}", order.getOrderNo(), refund.getRefundNo());
        return refund.getId();
    }

    @Override
    public IPage<RefundOrderVO> pageMyRefunds(Long pageNum, Long pageSize, Integer status) {
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getStudentId() == null) {
            throw new BusinessException(403, "仅家长可查看本人的退款单");
        }
        LambdaQueryWrapper<RefundOrder> wrapper = new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getStudentId, scope.getStudentId())
                .eq(status != null, RefundOrder::getStatus, status)
                .orderByDesc(RefundOrder::getId);
        return pageRefunds(pageNum, pageSize, wrapper);
    }

    @Override
    public IPage<RefundOrderVO> pageAllRefunds(Long pageNum, Long pageSize, Integer status, String orderNo, Long studentId) {
        LambdaQueryWrapper<RefundOrder> wrapper = new LambdaQueryWrapper<RefundOrder>()
                .eq(status != null, RefundOrder::getStatus, status)
                .eq(studentId != null, RefundOrder::getStudentId, studentId)
                .like(StringUtils.hasText(orderNo), RefundOrder::getOrderNo, orderNo)
                .orderByDesc(RefundOrder::getId);
        return pageRefunds(pageNum, pageSize, wrapper);
    }

    private IPage<RefundOrderVO> pageRefunds(Long pageNum, Long pageSize, LambdaQueryWrapper<RefundOrder> wrapper) {
        Page<RefundOrder> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE
                        : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        IPage<RefundOrder> refundPage = page(page, wrapper);
        Page<RefundOrderVO> result = new Page<>(refundPage.getCurrent(), refundPage.getSize(), refundPage.getTotal());
        result.setRecords(convert(refundPage.getRecords()));
        return result;
    }

    @Override
    public RefundPreviewVO preview(Long orderId) {
        OrderInfo order = requireOrder(orderId);
        checkOrderAccess(order);
        List<RefundableTaskVO> refundable = deliveryTaskService.listRefundableTasks(orderId);
        int boxes = refundable.stream().mapToInt(RefundableTaskVO::getRefundableBoxes).sum();
        Accumulation accumulation = accumulation(orderId);
        RefundPreviewVO vo = new RefundPreviewVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderStatus(order.getStatus());
        vo.setOrderStatusText(statusText(order.getStatus()));
        vo.setContractTotalBoxes(order.getContractTotalBoxes());
        vo.setRefundableBoxes(boxes);
        vo.setRefundableTasks(refundable);
        vo.setRefundedBoxes(accumulation.boxes());
        vo.setRefundedAmount(accumulation.amount());
        // 与 executeRefund 共用同一个私有计算方法（R7：预览口径 = 执行口径）
        vo.setEstimatedAmount(boxes <= 0 ? BigDecimal.ZERO
                : computeRefundAmount(order, refundable, boxes, accumulation));
        vo.setHasActiveRefund(hasActiveRefund(orderId));
        return vo;
    }

    // ==================== 审核 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, RefundAuditRequest request) {
        RefundOrder refund = requireRefund(id);
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        int fromStatus = refund.getStatus();
        int toStatus = approved ? RefundStatus.AUDITED.getCode() : RefundStatus.REJECTED.getCode();
        String remark = StringUtils.hasText(request.getRemark())
                ? request.getRemark() : (approved ? "审核通过" : "审核拒绝");
        // 统一迁移出口：规则白名单（AUDIT/REJECT 仅从 1 允许）+ CAS + 留痕
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_REFUND)
                        .action(approved ? StateTransitions.ACTION_AUDIT : StateTransitions.ACTION_REJECT)
                        .sceneText("退款单")
                        .entityType("refund_order")
                        .entityId(refund.getId())
                        .bizNo(refund.getRefundNo())
                        .fromStatus(fromStatus)
                        .toStatus(toStatus)
                        .conflictMessage("退款单状态已变更，请刷新后重试")
                        .remark(remark)
                        .build(),
                () -> lambdaUpdate()
                        .eq(RefundOrder::getId, refund.getId())
                        .eq(RefundOrder::getStatus, fromStatus)
                        .set(RefundOrder::getStatus, toStatus)
                        .set(RefundOrder::getAuditUserId, currentUserId())
                        .set(RefundOrder::getAuditTime, LocalDateTime.now())
                        .set(RefundOrder::getAuditRemark, remark)
                        .update());
    }

    // ==================== 执行退款 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeRefund(Long id) {
        RefundOrder refund = requireRefund(id);
        // 资金动作前置闸门：规则被管理端停用时必须显式失败（attempt 会静默跳过且不写台账），
        // 否则"退款执行"会在无人察觉的情况下不落账
        processTransitionExecutor.requireAllowed(StateTransitions.SCENE_REFUND,
                StateTransitions.ACTION_EXECUTE, refund.getStatus(), "退款单");
        if (!RefundStatus.AUDITED.getCode().equals(refund.getStatus())) {
            throw new BusinessException("仅「已审核待退款」的退款单可执行退款");
        }
        OrderInfo order = requireOrder(refund.getOrderId());

        // 1. 顺序硬约束：先抢履约（候选集 → 逐条 CAS 作废，只认返回 true），再计价。
        //    反过来"先按候选集计价再作废"会把并发输掉的任务也退钱。
        List<RefundableTaskVO> candidates = deliveryTaskService.listRefundableTasks(order.getId());
        if (candidates.isEmpty()) {
            throw new BusinessException("该订单当前无可退期次（可能已被配送或已退款）");
        }
        List<RefundableTaskVO> settled = new ArrayList<>();
        Set<Long> quotaProductIds = new LinkedHashSet<>();
        int boxes = 0;
        for (RefundableTaskVO candidate : candidates) {
            if (candidate.isPending()
                    && !deliveryTaskService.cancelTaskForRefund(candidate.getTaskId(), refund.getRefundNo())) {
                continue; // CAS 落败：该期次已被并发送出/取消 → 奶照送、钱不退它
            }
            settled.add(candidate);
            boxes += candidate.getRefundableBoxes();
            // 配额回补（R10）只对本次真正作废的零散单任务；缺货取消任务在取消时已按台账回补过，不重复回补
            if (candidate.isPending() && order.getPackageId() == null) {
                quotaProductIds.add(candidate.getProductId());
            }
        }
        if (settled.isEmpty() || boxes <= 0) {
            throw new BusinessException("可退期次已被并发处理（或仅剩免费补送盒），本次退款未执行，请刷新后重试");
        }

        // 2. 按实际作废成功集合计价（累计制：与同订单既有已退款单的累计值对齐，杜绝逐笔舍入漂移）
        Accumulation accumulation = accumulation(order.getId());
        BigDecimal amount = computeRefundAmount(order, settled, boxes, accumulation);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            // 累计已退满实付金额：拒绝执行（本事务回滚，连同上面已作废的任务一起回滚），
            // 不允许出现"任务作废了但没退钱"的静默结果
            throw new BusinessException("该订单累计退款金额已达实付金额，无可退金额，本次退款未执行");
        }

        // 3. 零散订购按台账回补配额（回到原池子；套餐不占每日配额，无需回补）
        for (Long productId : quotaProductIds) {
            dailyQuotaService.restoreForOrderProductDate(order.getId(), productId, null);
        }

        // 4. 资金侧 CAS 2→3（require：规则禁止/并发冲突抛业务异常，整体回滚，不留静默失败）
        int cumulativeBoxes = accumulation.boxes() + boxes;
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_REFUND)
                        .action(StateTransitions.ACTION_EXECUTE)
                        .sceneText("退款单")
                        .entityType("refund_order")
                        .entityId(refund.getId())
                        .bizNo(refund.getRefundNo())
                        .fromStatus(RefundStatus.AUDITED.getCode())
                        .toStatus(RefundStatus.REFUNDED.getCode())
                        .conflictMessage("退款单状态已变更，请刷新后重试")
                        .remark("执行退款 " + boxes + " 盒 / " + amount + " 元")
                        .build(),
                () -> lambdaUpdate()
                        .eq(RefundOrder::getId, refund.getId())
                        .eq(RefundOrder::getStatus, RefundStatus.AUDITED.getCode())
                        .set(RefundOrder::getStatus, RefundStatus.REFUNDED.getCode())
                        .set(RefundOrder::getRefundAmount, amount)
                        .set(RefundOrder::getRefundedBoxes, cumulativeBoxes)
                        .set(RefundOrder::getRefundChannel, REFUND_CHANNEL_MOCK)
                        .set(RefundOrder::getRefundTime, LocalDateTime.now())
                        .update());

        // 5. 实时通道：任务批量作废 → 父订单重新聚合（同事务落待办）。
        //    走待办而非直连 OrderInfoService，避免 order ↔ refund 环形依赖（设计方案 §5.8）
        pendingTaskService.enqueue(StateTransitions.SCENE_ORDER, "order_info", order.getId(),
                order.getOrderNo(), StateTransitions.ACTION_TASK_CANCEL);
        log.info("[退款] 退款单 {} 执行完成：订单 {}，作废 {} 盒，退款 {} 元",
                refund.getRefundNo(), order.getOrderNo(), boxes, amount);
    }

    /**
     * 退款金额计算（预览与执行**共用**，R7 口径一致）。
     *
     * <ul>
     *   <li>零散订购：明细快照单价 × 可退盒数（精确，零散单 pay_amount 即明细小计之和、无优惠）；</li>
     *   <li>学期套餐：{@code payAmount × 累计盒数 / 合同总盒数 − 已退累计}（按盒均摊 + 累计制防尾差）；</li>
     *   <li>两者最后都以 {@code payAmount − 已退金额} 为硬上限，保证 Σ(全部退款) ≤ pay_amount。</li>
     * </ul>
     */
    private BigDecimal computeRefundAmount(OrderInfo order, List<RefundableTaskVO> tasks, int boxes,
                                           Accumulation accumulation) {
        BigDecimal payAmount = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        BigDecimal remain = payAmount.subtract(accumulation.amount());
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount;
        if (order.getPackageId() == null) {
            Map<Long, BigDecimal> prices = itemPrices(order.getId());
            BigDecimal fallbackUnit = averageUnitPrice(order, payAmount);
            amount = BigDecimal.ZERO;
            for (RefundableTaskVO task : tasks) {
                BigDecimal price = prices.get(task.getProductId());
                if (price == null) {
                    price = fallbackUnit; // 历史数据缺明细时退化为盒均摊，不因数据缺口卡住退款
                }
                amount = amount.add(price.multiply(BigDecimal.valueOf(task.getRefundableBoxes())));
            }
        } else {
            Integer totalBoxes = order.getContractTotalBoxes();
            if (totalBoxes == null || totalBoxes <= 0) {
                throw new BusinessException("订单合同总盒数缺失，无法计算退款金额，请联系管理员处理");
            }
            amount = payAmount.multiply(BigDecimal.valueOf(accumulation.boxes() + (long) boxes))
                    .divide(BigDecimal.valueOf(totalBoxes), 2, RoundingMode.HALF_UP)
                    .subtract(accumulation.amount());
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            amount = BigDecimal.ZERO;
        }
        return amount.compareTo(remain) > 0 ? remain : amount;
    }

    private BigDecimal averageUnitPrice(OrderInfo order, BigDecimal payAmount) {
        Integer totalBoxes = order.getContractTotalBoxes();
        if (totalBoxes == null || totalBoxes <= 0) {
            return BigDecimal.ZERO;
        }
        return payAmount.divide(BigDecimal.valueOf(totalBoxes), 6, RoundingMode.HALF_UP);
    }

    private Map<Long, BigDecimal> itemPrices(Long orderId) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (OrderItem item : orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId))) {
            if (item.getProductId() != null && item.getPrice() != null) {
                prices.putIfAbsent(item.getProductId(), item.getPrice());
            }
        }
        return prices;
    }

    /** 该订单的退款累计值：盒数取已退款单的最大累计值（refunded_boxes 本身即累计口径），金额取合计 */
    private Accumulation accumulation(Long orderId) {
        List<RefundOrder> refunded = baseMapper.selectList(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getOrderId, orderId)
                .eq(RefundOrder::getStatus, RefundStatus.REFUNDED.getCode()));
        int boxes = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (RefundOrder record : refunded) {
            boxes = Math.max(boxes, record.getRefundedBoxes() == null ? 0 : record.getRefundedBoxes());
            amount = amount.add(record.getRefundAmount() == null ? BigDecimal.ZERO : record.getRefundAmount());
        }
        return new Accumulation(boxes, amount);
    }

    private boolean hasActiveRefund(Long orderId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getOrderId, orderId)
                .in(RefundOrder::getStatus, RefundStatus.PENDING_AUDIT.getCode(), RefundStatus.AUDITED.getCode()));
        return count != null && count > 0;
    }

    /** 累计值（盒数/金额），用于累计制计价 */
    private record Accumulation(int boxes, BigDecimal amount) {
    }

    // ==================== 退订联动（R4） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFullRefundForCancelledOrder(OrderInfo order) {
        if (order == null || order.getPayAmount() == null
                || order.getPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return null; // 未支付/零金额订单不产生资金记录
        }
        int boxes = order.getContractTotalBoxes() == null ? 0 : order.getContractTotalBoxes();
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo(nextNo("RF"));
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setStudentId(order.getStudentId());
        refund.setUserId(currentUserId() == null ? order.getUserId() : currentUserId());
        refund.setApplyBoxCount(boxes);
        refund.setRefundedBoxes(boxes);
        refund.setRefundAmount(order.getPayAmount());
        // 建单即终态：规则表描述的是 1→2→3 的合法迁移路径，不存在"无状态→已退款"的迁移，
        // 因此这里不经迁移出口；本次退订本身的 ORDER/CANCEL 迁移已在过程层留痕
        refund.setStatus(RefundStatus.REFUNDED.getCode());
        refund.setApplyReason("订单退订，全额退款");
        refund.setRefundChannel(REFUND_CHANNEL_MOCK);
        refund.setRefundTime(LocalDateTime.now());
        baseMapper.insert(refund);
        log.info("[退款] 订单 {} 退订，补齐全额退款记录 {}（{} 元）",
                order.getOrderNo(), refund.getRefundNo(), order.getPayAmount());
        return refund.getId();
    }

    // ==================== 毕业清算（R8） ====================

    @Override
    public SettlementResultVO settleStudent(Long studentId) {
        if (studentId == null) {
            throw new BusinessException("学生不能为空");
        }
        List<OrderInfo> orders = orderInfoMapper.selectList(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getStudentId, studentId)
                .in(OrderInfo::getStatus, OrderStatus.PENDING_PAYMENT.getCode(),
                        OrderStatus.PAID.getCode(), OrderStatus.DELIVERING.getCode())
                .orderByAsc(OrderInfo::getId));
        List<SettlementItemVO> items = new ArrayList<>();
        int cancelled = 0;
        int refunded = 0;
        int skipped = 0;
        int failed = 0;
        BigDecimal totalRefund = BigDecimal.ZERO;
        for (OrderInfo order : orders) {
            try {
                // 经自代理逐单调用：每单独立事务，单笔失败不影响其余
                SettlementItemVO item = self.settleOneOrder(order.getId());
                items.add(item);
                switch (item.getAction()) {
                    case "CANCELLED_UNPAID" -> cancelled++;
                    case "REFUNDED" -> {
                        refunded++;
                        totalRefund = totalRefund.add(item.getRefundAmount() == null
                                ? BigDecimal.ZERO : item.getRefundAmount());
                    }
                    case "FAILED" -> failed++;
                    default -> skipped++;
                }
            } catch (Exception e) {
                failed++;
                SettlementItemVO item = new SettlementItemVO();
                item.setOrderId(order.getId());
                item.setOrderNo(order.getOrderNo());
                item.setOrderStatus(order.getStatus());
                item.setAction("FAILED");
                item.setMessage("清算失败：" + e.getMessage());
                items.add(item);
                log.warn("[毕业清算] 订单 {} 清算失败：{}", order.getOrderNo(), e.getMessage());
            }
        }
        SettlementResultVO result = new SettlementResultVO();
        result.setStudentId(studentId);
        result.setTotalOrders(orders.size());
        result.setCancelledCount(cancelled);
        result.setRefundedCount(refunded);
        result.setSkippedCount(skipped);
        result.setFailedCount(failed);
        result.setTotalRefundAmount(totalRefund);
        result.setItems(items);
        log.info("[毕业清算] 学生 {} 清算完成：待清算 {} 单，取消 {} 单，退款 {} 单，跳过 {} 单，失败 {} 单",
                studentId, orders.size(), cancelled, refunded, skipped, failed);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SettlementItemVO settleOneOrder(Long orderId) {
        OrderInfo order = orderInfoMapper.selectById(orderId);
        SettlementItemVO item = new SettlementItemVO();
        if (order == null) {
            item.setAction("SKIPPED");
            item.setMessage("订单不存在");
            return item;
        }
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setOrderStatus(order.getStatus());

        if (OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return cancelUnpaidOrderForSettlement(order, item);
        }
        if (OrderStatus.CANCELLED.getCode().equals(order.getStatus())
                || OrderStatus.COMPLETED.getCode().equals(order.getStatus())) {
            item.setAction("SKIPPED");
            item.setMessage(statusText(order.getStatus()) + "订单无需清算");
            return item;
        }
        // 已支付/配送中：按整单可退期次生成退款单并立即执行（审核与执行同事务，迁移留痕完整）
        List<RefundableTaskVO> refundable = deliveryTaskService.listRefundableTasks(orderId);
        if (refundable.isEmpty()) {
            item.setAction("SKIPPED");
            item.setMessage("无可退期次（已全部配送或已退款）");
            return item;
        }
        Long refundId = createAuditedRefundForSettlement(order, refundable);
        // 经自代理调用（同事务传播）：失败即回滚本单（含刚建的退款单），由外层逐单 catch 记录
        self.executeRefund(refundId);
        RefundOrder executed = baseMapper.selectById(refundId);
        item.setAction("REFUNDED");
        item.setRefundNo(executed == null ? null : executed.getRefundNo());
        item.setRefundedBoxes(executed == null ? null : executed.getRefundedBoxes());
        item.setRefundAmount(executed == null ? null : executed.getRefundAmount());
        item.setMessage("已按可退期次退款");
        return item;
    }

    /**
     * 清算专属取消路径（R8）：不复用 {@code cancelTimeoutOrder}——它"未超时直接 return false"会让
     * 未到超时窗口的待支付单清不掉，且查单对账会把"已扣款未回调"的订单补成已支付（与清算意图相反）。
     */
    private SettlementItemVO cancelUnpaidOrderForSettlement(OrderInfo order, SettlementItemVO item) {
        boolean cancelled = processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_CANCEL)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(order.getId())
                        .bizNo(order.getOrderNo())
                        .fromStatus(OrderStatus.PENDING_PAYMENT.getCode())
                        .toStatus(OrderStatus.CANCELLED.getCode())
                        .remark("毕业清算：取消未支付订单")
                        .build(),
                () -> orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, order.getId())
                        .eq(OrderInfo::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                        .set(OrderInfo::getStatus, OrderStatus.CANCELLED.getCode())
                        .set(OrderInfo::getCancelTime, LocalDateTime.now())
                        .set(OrderInfo::getCancelReason, "毕业清算：未支付订单自动取消")) > 0);
        if (!cancelled) {
            item.setAction("SKIPPED");
            item.setMessage("订单状态已变更，本轮未处理");
            return item;
        }
        voidPendingRecords(order.getId(), "毕业清算：未支付订单取消，待支付流水作废");
        item.setAction("CANCELLED_UNPAID");
        item.setMessage("待支付订单已取消，待支付流水已作废");
        return item;
    }

    /** 清算用退款单：建单（待审核）→ 统一出口审核通过（1→2），随后由 executeRefund 执行（2→3） */
    private Long createAuditedRefundForSettlement(OrderInfo order, List<RefundableTaskVO> refundable) {
        int boxes = refundable.stream().mapToInt(RefundableTaskVO::getRefundableBoxes).sum();
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo(nextNo("RF"));
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setStudentId(order.getStudentId());
        refund.setUserId(currentUserId());
        refund.setApplyBoxCount(boxes);
        refund.setRefundedBoxes(0);
        refund.setStatus(RefundStatus.PENDING_AUDIT.getCode());
        refund.setApplyReason("毕业清算：整单可退期次退款");
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(refund));
        if (!inserted) {
            throw new BusinessException("该订单已有进行中的退款单，请先处理该退款单后再清算");
        }
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_REFUND)
                        .action(StateTransitions.ACTION_AUDIT)
                        .sceneText("退款单")
                        .entityType("refund_order")
                        .entityId(refund.getId())
                        .bizNo(refund.getRefundNo())
                        .fromStatus(RefundStatus.PENDING_AUDIT.getCode())
                        .toStatus(RefundStatus.AUDITED.getCode())
                        .conflictMessage("退款单状态已变更，请刷新后重试")
                        .remark("毕业清算：自动审核通过")
                        .build(),
                () -> lambdaUpdate()
                        .eq(RefundOrder::getId, refund.getId())
                        .eq(RefundOrder::getStatus, RefundStatus.PENDING_AUDIT.getCode())
                        .set(RefundOrder::getStatus, RefundStatus.AUDITED.getCode())
                        .set(RefundOrder::getAuditUserId, currentUserId())
                        .set(RefundOrder::getAuditTime, LocalDateTime.now())
                        .set(RefundOrder::getAuditRemark, "毕业清算自动审核")
                        .update());
        return refund.getId();
    }

    /**
     * 作废订单的历史待支付流水（微信预下单后未支付即清算的场景）。
     *
     * <p>与 {@code OrderInfoServiceImpl#voidPendingRecords} 同口径；此处按 Mapper 落地而非注入
     * OrderInfoService，是为了不引入 order ↔ refund 的环形依赖（见类注释）。</p>
     */
    private void voidPendingRecords(Long orderId, String remark) {
        List<PaymentRecord> pendings = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderId, orderId)
                .eq(PaymentRecord::getStatus, 1));
        for (PaymentRecord pending : pendings) {
            pending.setStatus(3);
            pending.setRemark(remark);
            paymentRecordMapper.updateById(pending);
        }
    }

    // ==================== 公共辅助 ====================

    private OrderInfo requireOrder(Long orderId) {
        if (orderId == null) {
            throw new BusinessException("订单不能为空");
        }
        OrderInfo order = orderInfoMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    private RefundOrder requireRefund(Long id) {
        RefundOrder refund = getById(id);
        if (refund == null) {
            throw new BusinessException("退款单不存在");
        }
        return refund;
    }

    /** 数据权限：家长仅能操作本人绑定学生的订单；班主任限本班；管理员不限（无登录上下文的内部调用跳过） */
    private void checkOrderAccess(OrderInfo order) {
        DataScope scope = dataScopeResolver.resolveQuietly();
        if (scope.getStudentId() != null && !scope.getStudentId().equals(order.getStudentId())) {
            throw new BusinessException(403, "无权操作该订单");
        }
        if (scope.getClassId() != null && !scope.getClassId().equals(order.getClassId())) {
            throw new BusinessException(403, "无权操作该班级的订单");
        }
    }

    private Long currentUserId() {
        String username = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(username)) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        return user == null ? null : user.getId();
    }

    private List<RefundOrderVO> convert(List<RefundOrder> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> studentIds = records.stream().map(RefundOrder::getStudentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> studentNames = studentIds.isEmpty() ? Collections.emptyMap()
                : studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Student::getStudentName, (a, b) -> a));
        return records.stream().map(record -> {
            RefundOrderVO vo = new RefundOrderVO();
            vo.setId(record.getId());
            vo.setRefundNo(record.getRefundNo());
            vo.setOrderId(record.getOrderId());
            vo.setOrderNo(record.getOrderNo());
            vo.setStudentId(record.getStudentId());
            vo.setStudentName(studentNames.get(record.getStudentId()));
            vo.setUserId(record.getUserId());
            vo.setApplyBoxCount(record.getApplyBoxCount());
            vo.setRefundedBoxes(record.getRefundedBoxes());
            vo.setRefundAmount(record.getRefundAmount());
            vo.setStatus(record.getStatus());
            vo.setStatusText(refundStatusText(record.getStatus()));
            vo.setApplyReason(record.getApplyReason());
            vo.setAuditRemark(record.getAuditRemark());
            vo.setAuditTime(record.getAuditTime());
            vo.setRefundTime(record.getRefundTime());
            vo.setCreateTime(record.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    private String refundStatusText(Integer status) {
        if (status == null) {
            return "";
        }
        for (RefundStatus value : RefundStatus.values()) {
            if (value.getCode().equals(status)) {
                return value.getDesc();
            }
        }
        return String.valueOf(status);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        for (OrderStatus value : OrderStatus.values()) {
            if (value.getCode().equals(status)) {
                return value.getDesc();
            }
        }
        return String.valueOf(status);
    }
}
