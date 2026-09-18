package com.milk.order.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.order.dto.CreateOrderRequest;
import com.milk.order.module.order.dto.OrderItemRequest;
import com.milk.order.module.order.dto.WechatPayNotifyRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.entity.PaymentRecord;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.mapper.OrderItemMapper;
import com.milk.order.module.order.mapper.PaymentRecordMapper;
import com.milk.order.module.order.pay.WechatPaySimulator;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.order.vo.OrderVO;
import com.milk.order.module.order.vo.WechatPayParamsVO;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.entity.MealPackageItem;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.MealPackageItemMapper;
import com.milk.order.module.product.mapper.MealPackageMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.dto.QuotaDeductItem;
import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.mapper.SysUserMapper;
import com.milk.order.module.user.service.DataScopeResolver;
import com.milk.order.process.ProcessTransitionExecutor;
import com.milk.order.process.TransitionSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    private final OrderItemMapper orderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final MealPackageMapper mealPackageMapper;
    private final MealPackageItemMapper mealPackageItemMapper;
    private final ProductMapper productMapper;
    private final SysUserMapper sysUserMapper;
    private final DailyQuotaService dailyQuotaService;
    private final DataScopeResolver dataScopeResolver;
    private final WechatPaySimulator wechatPaySimulator;
    private final DeliveryTaskService deliveryTaskService;
    private final ProcessTransitionExecutor processTransitionExecutor;

    /**
     * 自代理：对账/查单补偿需要走 @Transactional 代理路径（同类内部直调不经过代理），
     * 注入自身接口代理保证每笔补偿独立事务，单笔失败不影响其余订单。
     */
    @Lazy
    @Autowired
    private OrderInfoService self;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter PAY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单号单调序列：同一秒内批量生成单号时，纯随机后缀可能撞唯一键 */
    private static final AtomicLong NO_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT)
                + String.format("%06d", NO_SEQ.incrementAndGet() % 1000000);
    }

    // ==================== 查询 ====================

    @Override
    public IPage<OrderVO> pageOrders(Long pageNum, Long pageSize, Long classId, Long studentId,
                                      Integer status, String startDate, String endDate) {
        // 数据权限：家长仅看自己绑定的学生，班主任仅看本班，管理员不限
        DataScope scope = dataScopeResolver.resolve();
        if (scope.getClassId() != null) {
            classId = scope.getClassId();
        }
        if (scope.getStudentId() != null) {
            studentId = scope.getStudentId();
        }

        Page<OrderInfo> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(classId != null, OrderInfo::getClassId, classId)
                .eq(studentId != null, OrderInfo::getStudentId, studentId)
                .eq(status != null, OrderInfo::getStatus, status);
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(OrderInfo::getCreateTime, startDate + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(OrderInfo::getCreateTime, endDate + " 23:59:59");
        }
        wrapper.orderByDesc(OrderInfo::getId);

        IPage<OrderInfo> orderPage = page(page, wrapper);
        List<OrderVO> voList = convert(orderPage.getRecords());

        Page<OrderVO> result = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public OrderVO getOrderDetail(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        List<OrderVO> list = convert(Collections.singletonList(order));
        OrderVO vo = list.get(0);
        vo.setItems(getOrderItems(id));
        return vo;
    }

    @Override
    public List<OrderItem> getOrderItems(Long orderId) {
        OrderInfo order = getById(orderId);
        if (order != null) {
            // 家长/班主任按数据范围校验归属（内部流程如续订无登录上下文时自动跳过）
            checkOrderAccess(order);
        }
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
    }

    // ==================== 创建订单 ====================

    @Override
    public Long createOrder(CreateOrderRequest request) {
        // 数据权限：家长仅能为自己绑定的学生下单，班主任仅能为本班学生下单
        checkCreateOrderScope(request);
        return doCreateOrder(request, currentUserId());
    }

    /**
     * 内部创建订单（支持指定下单人，供定时任务/续订等无登录上下文场景调用）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long doCreateOrder(CreateOrderRequest request, Long userId) {
        // 1. 校验学生与班级
        Student student = studentMapper.selectById(request.getStudentId());
        if (student == null) {
            throw new BusinessException("学生不存在");
        }
        ClassInfo clazz = classInfoMapper.selectById(student.getClassId());
        if (clazz == null) {
            throw new BusinessException("学生所属班级不存在");
        }
        // 2. 校验配送日期
        if (request.getDeliveryEndDate().isBefore(request.getDeliveryStartDate())) {
            throw new BusinessException("配送结束日期不能早于开始日期");
        }
        // 零散订购（无套餐）为单日补购：仅允许起止同日；学期套餐为周期配送
        if (request.getPackageId() == null
                && !request.getDeliveryStartDate().equals(request.getDeliveryEndDate())) {
            throw new BusinessException("单日零散订购仅支持选择一个配送日期；周期订购请使用学期套餐");
        }
        // 2.1 业务说明：学期套餐覆盖日内也允许单日散订（换口味/加购），仅做配额校验

        // 3. 计算订单明细：套餐订单以套餐固定配置为准（服务端权威，家长不可自选），散订/购物车使用传入明细
        List<OrderItemRequest> items;
        if (request.getPackageId() != null) {
            List<MealPackageItem> pkgItems = mealPackageItemMapper.selectList(
                    new LambdaQueryWrapper<MealPackageItem>().eq(MealPackageItem::getPackageId, request.getPackageId()));
            if (CollectionUtils.isEmpty(pkgItems)) {
                throw new BusinessException("套餐未配置配送明细，请先在管理端完成套餐配置");
            }
            items = pkgItems.stream().map(pi -> {
                OrderItemRequest req = new OrderItemRequest();
                req.setProductId(pi.getProductId());
                req.setQuantity(pi.getQuantity());
                return req;
            }).collect(Collectors.toList());
        } else {
            if (CollectionUtils.isEmpty(request.getItems())) {
                throw new BusinessException("订单明细不能为空");
            }
            items = request.getItems();
        }
        Set<Long> productIds = items.stream()
                .map(OrderItemRequest::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItemRequest item : items) {
            if (!productMap.containsKey(item.getProductId())) {
                throw new BusinessException("奶品不存在：id=" + item.getProductId());
            }
        }

        // 3.1 零散订购预检各品种当日机动配额（只读校验，权威扣减在支付成功事务中）
        if (request.getPackageId() == null) {
            Map<Long, Integer> need = new LinkedHashMap<>();
            for (OrderItemRequest item : items) {
                need.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
            for (Map.Entry<Long, Integer> entry : need.entrySet()) {
                int available = dailyQuotaService.remaining(entry.getKey(), request.getDeliveryStartDate());
                if (entry.getValue() > available) {
                    Product p = productMapper.selectById(entry.getKey());
                    String name = p == null ? "奶品" + entry.getKey() : p.getProductName();
                    throw new BusinessException("「" + name + "」" + request.getDeliveryStartDate()
                            + " 剩余库存 " + available + " 盒，不足本次订购的 " + entry.getValue() + " 盒，卖完即止");
                }
            }
        }

        // 4. 金额：有套餐用套餐价，否则按明细单价×数量
        BigDecimal totalAmount;
        BigDecimal payAmount;
        Integer orderType = 1;
        String packageName = null;
        if (request.getPackageId() != null) {
            MealPackage pkg = mealPackageMapper.selectById(request.getPackageId());
            if (pkg == null) {
                throw new BusinessException("套餐不存在");
            }
            orderType = pkg.getPackageType();
            packageName = pkg.getPackageName();
            totalAmount = pkg.getOriginalPrice() == null ? BigDecimal.ZERO : pkg.getOriginalPrice();
            // 套餐未配置折扣价时按原价支付，避免 pay_amount 为空导致支付失败
            payAmount = pkg.getDiscountPrice() == null ? totalAmount : pkg.getDiscountPrice();
        } else {
            totalAmount = BigDecimal.ZERO;
            for (OrderItemRequest item : items) {
                Product p = productMap.get(item.getProductId());
                BigDecimal subtotal = p.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                totalAmount = totalAmount.add(subtotal);
            }
            payAmount = totalAmount;
        }
        BigDecimal discountAmount = totalAmount.subtract(payAmount);
        if (discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            discountAmount = BigDecimal.ZERO;
        }

        // 5. 下单人（由调用方传入，管理端代下单用当前登录用户，续订用原订单家长）

        // 6. 生成订单号
        String orderNo = nextNo("MO");

        // 7. 保存订单
        OrderInfo order = new OrderInfo();
        order.setOrderNo(orderNo);
        order.setStudentId(student.getId());
        order.setUserId(userId);
        order.setClassId(student.getClassId());
        order.setPackageId(request.getPackageId());
        order.setOrderType(orderType);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(payAmount);
        order.setDiscountAmount(discountAmount);
        order.setDeliveryStartDate(request.getDeliveryStartDate());
        order.setDeliveryEndDate(request.getDeliveryEndDate());
        order.setRemark(request.getRemark());
        save(order);

        // 8. 保存明细（快照奶品名称/规格/单价）
        for (OrderItemRequest item : items) {
            Product p = productMap.get(item.getProductId());
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setProductId(p.getId());
            oi.setProductName(p.getProductName());
            oi.setSpec(p.getSpec());
            oi.setPrice(p.getPrice());
            oi.setQuantity(item.getQuantity());
            oi.setSubtotal(p.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            orderItemMapper.insert(oi);
        }

        return order.getId();
    }

    // ==================== 续订订单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long renewOrder(Long originalOrderId) {
        OrderInfo original = getOrder(originalOrderId);
        Integer status = original.getStatus();
        if (!OrderStatus.PAID.getCode().equals(status)
                && !OrderStatus.DELIVERING.getCode().equals(status)
                && !OrderStatus.COMPLETED.getCode().equals(status)) {
            throw new BusinessException("仅已支付/配送中/已完成订单可续订");
        }
        // 复制明细
        List<OrderItem> originalItems = getOrderItems(originalOrderId);
        if (originalItems.isEmpty()) {
            throw new BusinessException("原订单无明细，无法续订");
        }
        List<OrderItemRequest> items = originalItems.stream().map(oi -> {
            OrderItemRequest req = new OrderItemRequest();
            req.setProductId(oi.getProductId());
            req.setQuantity(oi.getQuantity());
            return req;
        }).collect(Collectors.toList());

        // 新配送周期：从原订单结束日+1天开始，按月续订加1个月
        LocalDate newStart = original.getDeliveryEndDate().plusDays(1);
        LocalDate newEnd = newStart.plusMonths(1).minusDays(1);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setStudentId(original.getStudentId());
        request.setPackageId(original.getPackageId());
        request.setDeliveryStartDate(newStart);
        request.setDeliveryEndDate(newEnd);
        request.setItems(items);
        request.setRemark("自动续订订单（原订单" + original.getOrderNo() + "）");

        // 创建新订单（下单人沿用原订单家长）
        Long newOrderId = doCreateOrder(request, original.getUserId());
        // 自动支付（续订默认已支付）
        payOrder(newOrderId);
        return newOrderId;
    }

    // ==================== 模拟支付（管理端/内部流程，同步完成） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        // 状态机规则校验（管理端可配置是否允许 待支付→已支付）
        processTransitionExecutor.requireAllowed(StateTransitions.SCENE_ORDER,
                StateTransitions.ACTION_PAY, order.getStatus(), "订单");
        if (order.getPayAmount() == null) {
            throw new BusinessException("订单支付金额缺失，无法支付，请联系管理员处理");
        }
        // 1. 先抢占状态（过程层统一出口：规则校验 + CAS 条件更新 + 迁移留痕）。
        //    顺序是关键：并发支付/并发回调下只有一方能把订单推进到「已支付」，落败方在此立即中止，
        //    于是后续的配额扣减、流水写入、任务展开都只由胜出者执行一次。
        //    若反过来「先做副作用、再 CAS」，落败方已经扣了配额、写了流水才失败，只能靠回滚收场，
        //    并发下还会互相撞业务唯一键（实验二暴露）。
        String transactionId = nextNo("MOCK");
        processTransitionExecutor.require(
                paidSpec(order, "模拟支付落账", "订单状态已变更，支付处理冲突，请刷新后重试"),
                () -> markOrderPaid(order, transactionId));
        // 2. 零散订购按品种扣减当日机动配额（含保质期内结转；学期套餐为全校统一预约定制，不占配额）
        if (order.getPackageId() == null) {
            dailyQuotaService.deduct(order.getId(), order.getDeliveryStartDate(), toQuotaItems(getOrderItems(id)));
        }
        // 3. 写支付记录
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(order.getId());
        record.setOrderNo(order.getOrderNo());
        record.setTransactionId(transactionId);
        record.setAmount(order.getPayAmount());
        record.setPayType(1);
        record.setStatus(2);
        record.setPayTime(LocalDateTime.now());
        record.setUserId(order.getUserId());
        record.setRemark("模拟支付");
        paymentRecordMapper.insert(record);
        // 4. 展开整个配送周期的配送任务与签收记录（幂等）
        deliveryTaskService.generateTasksForOrder(order);
    }

    // ==================== 微信支付（模拟）：预下单 + 回调处理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WechatPayParamsVO prepayOrder(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        processTransitionExecutor.requireAllowed(StateTransitions.SCENE_ORDER,
                StateTransitions.ACTION_PAY, order.getStatus(), "订单");
        if (order.getPayAmount() == null) {
            throw new BusinessException("订单支付金额缺失，无法支付，请联系管理员处理");
        }
        // 1. 作废该订单历史待支付流水（重复发起支付场景）
        voidPendingRecords(id, "重新发起支付，原待支付流水作废");

        // 2. 调模拟微信统一下单，获取预支付凭证与调起参数
        WechatPayParamsVO params = wechatPaySimulator.unifiedOrder(order);

        // 3. 写待支付流水（回调成功后更新为已支付；该流水时间即支付超时判定的锚点）
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(order.getId());
        record.setOrderNo(order.getOrderNo());
        record.setAmount(order.getPayAmount());
        record.setPayType(1);
        record.setStatus(1);
        record.setUserId(order.getUserId());
        record.setRemark("微信支付（模拟）预下单，prepayId=" + params.getPrepayId());
        paymentRecordMapper.insert(record);

        return params;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleWechatPayNotify(WechatPayNotifyRequest notify) {
        if (notify == null || !"SUCCESS".equals(notify.getResultCode())) {
            log.warn("[模拟微信支付] 回调结果非 SUCCESS，忽略：{}", notify == null ? "null" : notify.getResultCode());
            return false;
        }
        OrderInfo order = lambdaQuery().eq(OrderInfo::getOrderNo, notify.getOutTradeNo()).one();
        if (order == null) {
            log.warn("[模拟微信支付] 回调订单不存在：{}", notify.getOutTradeNo());
            return false;
        }
        // 幂等（快速路径）：已支付及后续状态直接返回成功，避免重复扣库存/重复流水
        if (OrderStatus.PAID.getCode().equals(order.getStatus())
                || OrderStatus.DELIVERING.getCode().equals(order.getStatus())
                || OrderStatus.COMPLETED.getCode().equals(order.getStatus())) {
            log.info("[模拟微信支付] 订单 {} 状态已为 {}，回调幂等处理", order.getOrderNo(), order.getStatus());
            return true;
        }
        // 订单已取消（如用户取消支付/超时取消后回调才到达）：拒绝本轮回调，不将已取消订单置为已支付
        if (!OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            log.warn("[模拟微信支付] 订单 {} 状态为 {}（已取消/非法），回调拒绝处理", order.getOrderNo(), order.getStatus());
            return false;
        }
        // 状态机规则：管理端禁用 待支付→已支付 时拒绝落账（微信将重试，规则恢复后自动补齐）
        if (!processTransitionExecutor.allowed(StateTransitions.SCENE_ORDER,
                StateTransitions.ACTION_PAY, order.getStatus())) {
            log.warn("[模拟微信支付] 订单 {} 状态迁移被状态机规则禁止（ORDER/PAY/{}），回调暂不处理",
                    order.getOrderNo(), order.getStatus());
            return false;
        }
        // 金额核对：回调金额必须与订单应付金额一致
        if (notify.getAmount() == null || order.getPayAmount() == null
                || notify.getAmount().compareTo(order.getPayAmount()) != 0) {
            log.warn("[模拟微信支付] 订单 {} 回调金额 {} 与应付金额 {} 不一致",
                    order.getOrderNo(), notify.getAmount(), order.getPayAmount());
            return false;
        }
        // 1. 先抢占状态（过程层统一出口）：并发双回调/回调与取消竞争时仅一方成功。
        //    落败方在此立即中止，配额扣减、流水更新、任务展开因而只由胜出者执行一次。
        //    若反过来「先做副作用、再 CAS」，落败方已扣配额/已写流水才失败，只能靠回滚，
        //    并发下还会互相撞业务唯一键（实验二暴露）。
        processTransitionExecutor.require(
                paidSpec(order, "微信支付回调落账", "订单状态已变更，回调处理冲突"),
                () -> markOrderPaid(order, notify.getTransactionId()));
        // 2. 零散订购扣减当日机动配额（含保质期内结转；学期套餐不占配额），不足则本轮回调整体回滚
        if (order.getPackageId() == null) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
            dailyQuotaService.deduct(order.getId(), order.getDeliveryStartDate(), toQuotaItems(items));
        }
        // 3. 支付流水：更新预下单的待支付流水为成功，缺失时补建
        PaymentRecord pending = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId, order.getId())
                        .eq(PaymentRecord::getStatus, 1)
                        .orderByDesc(PaymentRecord::getId)
                        .last("LIMIT 1"));
        LocalDateTime payTime;
        try {
            payTime = StringUtils.hasText(notify.getPayTime())
                    ? LocalDateTime.parse(notify.getPayTime(), PAY_TIME_FMT)
                    : LocalDateTime.now();
        } catch (Exception e) {
            log.warn("[模拟微信支付] 回调支付时间格式非法：{}，按当前时间处理", notify.getPayTime());
            payTime = LocalDateTime.now();
        }
        if (pending != null) {
            pending.setStatus(2);
            pending.setTransactionId(notify.getTransactionId());
            pending.setPayTime(payTime);
            pending.setRemark("微信支付（模拟）回调成功");
            paymentRecordMapper.updateById(pending);
        } else {
            PaymentRecord record = new PaymentRecord();
            record.setOrderId(order.getId());
            record.setOrderNo(order.getOrderNo());
            record.setTransactionId(notify.getTransactionId());
            record.setAmount(notify.getAmount());
            record.setPayType(1);
            record.setStatus(2);
            record.setPayTime(payTime);
            record.setUserId(order.getUserId());
            record.setRemark("微信支付（模拟）回调成功（无预下单流水，补建）");
            paymentRecordMapper.insert(record);
        }
        // 4. 展开整个配送周期的配送任务与签收记录（同一事务，幂等）
        deliveryTaskService.generateTasksForOrder(order);
        log.info("[模拟微信支付] 订单 {} 支付成功，流水号 {}", order.getOrderNo(), notify.getTransactionId());
        return true;
    }

    // ==================== 支付查单 / 对账补偿 / 超时取消 ====================

    @Override
    public Integer queryPayResult(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        if (!OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return order.getStatus();
        }
        // 待支付：主动向微信侧查单——用户已扣款但回调丢失时补偿落账（前端轮询兜底）
        WechatPaySimulator.PaidOrder paid = wechatPaySimulator.queryOrder(order.getOrderNo());
        if (paid != null) {
            try {
                self.handleWechatPayNotify(toNotify(paid));
            } catch (Exception e) {
                log.warn("[模拟微信支付] 订单 {} 查单补偿处理失败：{}", order.getOrderNo(), e.getMessage());
            }
            OrderInfo latest = getById(id);
            return latest == null ? order.getStatus() : latest.getStatus();
        }
        return order.getStatus();
    }

    @Override
    public int reconcilePendingPayments() {
        // 只处理"存在待支付流水且已过在途窗口"的订单：刚发起支付 2 分钟内回调仍在路上，不抢跑
        LocalDateTime inFlightBefore = LocalDateTime.now().minusMinutes(2);
        List<PaymentRecord> pendings = paymentRecordMapper.selectList(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getStatus, 1)
                        .lt(PaymentRecord::getCreateTime, inFlightBefore));
        if (pendings.isEmpty()) {
            return 0;
        }
        Set<String> orderNos = pendings.stream()
                .map(PaymentRecord::getOrderNo).collect(Collectors.toSet());
        int count = 0;
        for (String orderNo : orderNos) {
            OrderInfo order = lambdaQuery().eq(OrderInfo::getOrderNo, orderNo).one();
            // 仅待支付订单需要补偿；已支付/已取消的悬挂流水由各自流程负责作废
            if (order == null || !OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
                continue;
            }
            WechatPaySimulator.PaidOrder paid = wechatPaySimulator.queryOrder(orderNo);
            if (paid == null) {
                // 微信侧未扣款：不补偿，留给超时取消任务处理
                continue;
            }
            try {
                if (self.handleWechatPayNotify(toNotify(paid))) {
                    count++;
                    log.info("[支付对账] 订单 {} 回调丢失，已查单补偿落账，流水号 {}", orderNo, paid.getTransactionId());
                }
            } catch (Exception e) {
                log.error("[支付对账] 订单 {} 补偿失败：{}", orderNo, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 过程聚合对账补偿（定时任务兜底）：扫描「父状态与子过程不一致」的订单并修复。
     *
     * <p>覆盖两类漂移：</p>
     * <ol>
     *   <li>订单仍为已支付，但已有子任务处于配送中 → 补偿推进为配送中（联动丢失）；</li>
     *   <li>订单仍为配送中，但子任务已全部到达终态 → 补偿推进为已完成（聚合回调丢失）。</li>
     * </ol>
     *
     * <p>补偿复用统一迁移出口与聚合出口，因此天然幂等：重复执行不会产生额外的状态变更。</p>
     *
     * @param limit 单轮单类最多处理的订单数，避免历史脏数据拖死定时任务
     * @return 实际修复的订单数
     */
    @Override
    public int reconcileOrderAggregation(int limit) {
        int safeLimit = Math.max(1, limit);
        int repaired = 0;
        // 场景一：已支付但子任务已开始配送 —— 补齐“配送中”联动
        List<OrderInfo> paidOrders = lambdaQuery()
                .eq(OrderInfo::getStatus, OrderStatus.PAID.getCode())
                .orderByAsc(OrderInfo::getId)
                .last("LIMIT " + safeLimit)
                .list();
        for (OrderInfo order : paidOrders) {
            if (deliveryTaskService.hasDispatchingTask(order.getId())
                    && self.markDeliveringIfPaid(order.getId())) {
                repaired++;
                log.info("[过程对账] 订单 {} 已支付但子任务已配送，补偿推进为配送中", order.getOrderNo());
            }
        }
        // 场景二：配送中但子任务全部到达终态 —— 补齐“已完成”聚合
        List<OrderInfo> deliveringOrders = lambdaQuery()
                .eq(OrderInfo::getStatus, OrderStatus.DELIVERING.getCode())
                .orderByAsc(OrderInfo::getId)
                .last("LIMIT " + safeLimit)
                .list();
        for (OrderInfo order : deliveringOrders) {
            if (self.completeOrderIfAllTasksDone(order.getId())) {
                repaired++;
                log.info("[过程对账] 订单 {} 子任务已全部终态，补偿聚合为已完成", order.getOrderNo());
            }
        }
        return repaired;
    }

    @Override
    public boolean cancelTimeoutOrder(Long id, int timeoutMinutes) {
        OrderInfo order = getById(id);
        if (order == null || !OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return false;
        }
        // 超时锚点：最近一次待支付流水时间（无流水则订单创建时间），
        // 用户刚重新发起过支付的订单不会因创建时间过老而被误杀
        PaymentRecord latestPending = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId, id)
                        .eq(PaymentRecord::getStatus, 1)
                        .orderByDesc(PaymentRecord::getId)
                        .last("LIMIT 1"));
        LocalDateTime anchor = latestPending != null && latestPending.getCreateTime() != null
                ? latestPending.getCreateTime() : order.getCreateTime();
        if (anchor == null || anchor.plusMinutes(timeoutMinutes).isAfter(LocalDateTime.now())) {
            return false; // 尚未超时
        }
        // 取消前查单对账：用户已实际扣款但回调丢失 → 补偿落账，绝不取消已付款订单
        WechatPaySimulator.PaidOrder paid = wechatPaySimulator.queryOrder(order.getOrderNo());
        if (paid != null) {
            try {
                self.handleWechatPayNotify(toNotify(paid));
                log.info("[支付超时任务] 订单 {} 已扣款未回调，转为补偿落账，不取消", order.getOrderNo());
            } catch (Exception e) {
                log.error("[支付超时任务] 订单 {} 补偿失败，本轮跳过取消：{}", order.getOrderNo(), e.getMessage());
            }
            return false;
        }
        // 状态机规则 + CAS 条件取消（过程层统一出口）：规则禁止、或与并发到达的支付回调竞争失败，
        // 都返回 false（本轮不取消，留待下一轮或回调处理），保持定时任务的幂等与可重复执行
        boolean cancelled = processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_CANCEL)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(id)
                        .bizNo(order.getOrderNo())
                        .fromStatus(OrderStatus.PENDING_PAYMENT.getCode())
                        .toStatus(OrderStatus.CANCELLED.getCode())
                        .remark("支付超时自动取消")
                        .build(),
                () -> lambdaUpdate()
                        .eq(OrderInfo::getId, id)
                        .eq(OrderInfo::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                        .set(OrderInfo::getStatus, OrderStatus.CANCELLED.getCode())
                        .set(OrderInfo::getCancelTime, LocalDateTime.now())
                        .set(OrderInfo::getCancelReason, "支付超时自动取消（超过" + timeoutMinutes + "分钟未支付）")
                        .update());
        if (!cancelled) {
            log.info("[支付超时任务] 订单 {} 本轮未取消（规则禁止或状态已被并发变更）", order.getOrderNo());
            return false;
        }
        voidPendingRecords(id, "订单支付超时自动取消，待支付流水作废");
        log.info("[支付超时任务] 订单 {} 超时未支付，已自动取消", order.getOrderNo());
        return true;
    }

    /** 微信侧支付结果转回调报文（查单/对账补偿复用回调处理路径） */
    private WechatPayNotifyRequest toNotify(WechatPaySimulator.PaidOrder paid) {
        WechatPayNotifyRequest notify = new WechatPayNotifyRequest();
        notify.setOutTradeNo(paid.getOutTradeNo());
        notify.setTransactionId(paid.getTransactionId());
        notify.setAmount(paid.getAmount());
        notify.setPayTime(paid.getPayTime() == null
                ? LocalDateTime.now().format(PAY_TIME_FMT) : paid.getPayTime().format(PAY_TIME_FMT));
        notify.setResultCode("SUCCESS");
        return notify;
    }

    /**
     * 订单置为已支付（统一出口，CAS 条件更新：仅 待支付→已支付 生效）
     *
     * @return false 表示订单已不在待支付状态（并发回调/并发支付/已取消），调用方据此回滚
     */
    private boolean markOrderPaid(OrderInfo order, String transactionId) {
        return lambdaUpdate()
                .eq(OrderInfo::getId, order.getId())
                .eq(OrderInfo::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                .set(OrderInfo::getStatus, OrderStatus.PAID.getCode())
                .set(OrderInfo::getPayTime, LocalDateTime.now())
                .set(OrderInfo::getPayType, 1)
                .set(OrderInfo::getTransactionId, transactionId)
                .update();
    }

    /** 订单「置为已支付」的迁移规格：模拟支付 / 微信回调 / 查单补偿共用同一收口 */
    private TransitionSpec paidSpec(OrderInfo order, String remark, String conflictMessage) {
        return TransitionSpec.builder()
                .scene(StateTransitions.SCENE_ORDER)
                .action(StateTransitions.ACTION_PAY)
                .sceneText("订单")
                .entityType("order_info")
                .entityId(order.getId())
                .bizNo(order.getOrderNo())
                .fromStatus(OrderStatus.PENDING_PAYMENT.getCode())
                .toStatus(OrderStatus.PAID.getCode())
                .conflictMessage(conflictMessage)
                .remark(remark)
                .build();
    }

    // ==================== 退订 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long id, String reason) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        Integer status = order.getStatus();
        // 退款闸门保险：订单仍是已支付但存在配送中任务（奶已实际送出）时同样拒绝退订
        if (deliveryTaskService.hasDispatchingTask(id)) {
            throw new BusinessException("订单已开始配送，不可退订，请联系管理员线下处理");
        }
        // 已支付的零散订购退订按台账回补配额（学期套餐不占配额，无需回补）
        if (OrderStatus.PAID.getCode().equals(status) && order.getPackageId() == null) {
            dailyQuotaService.restore(id);
        }
        // 作废待支付流水（微信预下单后未支付即取消的场景），防止残留悬挂的待支付记录
        voidPendingRecords(id, "订单取消，待支付流水作废");
        // 状态机规则 + CAS 乐观取消（过程层统一出口）：以读取时的状态为条件，
        // 与并发的支付回调/超时取消竞争，仅一方成功；失败抛异常回滚本轮配额回补与流水作废
        String cancelReason = StringUtils.hasText(reason) ? reason : "用户退订";
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_CANCEL)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(id)
                        .bizNo(order.getOrderNo())
                        .fromStatus(status)
                        .toStatus(OrderStatus.CANCELLED.getCode())
                        .conflictMessage("订单状态已变更，请刷新后重试")
                        .remark(cancelReason)
                        .build(),
                () -> lambdaUpdate()
                        .eq(OrderInfo::getId, id)
                        .eq(OrderInfo::getStatus, status)
                        .set(OrderInfo::getStatus, OrderStatus.CANCELLED.getCode())
                        .set(OrderInfo::getCancelTime, LocalDateTime.now())
                        .set(OrderInfo::getCancelReason, cancelReason)
                        .update());
        // 联动作废支付后已生成的未签收配送任务，避免退订后仍可签收
        deliveryTaskService.cancelPendingTasksForOrder(id);
    }

    // ==================== 配送/完成 ====================

    @Override
    public boolean markDeliveringIfPaid(Long orderId) {
        OrderInfo order = getById(orderId);
        if (order == null || !OrderStatus.PAID.getCode().equals(order.getStatus())) {
            return false;
        }
        // 过程层宽松迁移：规则禁止或并发竞争失败都返回 false，调用方按幂等语义处理
        return processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_DELIVER)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(orderId)
                        .bizNo(order.getOrderNo())
                        .fromStatus(OrderStatus.PAID.getCode())
                        .toStatus(OrderStatus.DELIVERING.getCode())
                        .remark("任务开始配送，联动订单进入配送中")
                        .build(),
                () -> lambdaUpdate()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getStatus, OrderStatus.PAID.getCode())
                        .set(OrderInfo::getStatus, OrderStatus.DELIVERING.getCode())
                        .update());
    }

    /**
     * 父子状态聚合出口：子过程（配送任务）全部到达终态时，把父过程（订单）推进到已完成。
     *
     * <p>这是「层级化业务状态管理」的落点——父状态不由某个子任务直接改写，而由子过程整体聚合决定；
     * 对账补偿任务也复用本出口修复父状态漂移。</p>
     */
    @Override
    public boolean completeOrderIfAllTasksDone(Long orderId) {
        OrderInfo order = getById(orderId);
        if (order == null || !OrderStatus.DELIVERING.getCode().equals(order.getStatus())) {
            return false;
        }
        if (deliveryTaskService.hasUnfinishedTask(orderId)) {
            return false;
        }
        boolean updated = processTransitionExecutor.attempt(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_AUTO_COMPLETE)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(orderId)
                        .bizNo(order.getOrderNo())
                        .fromStatus(OrderStatus.DELIVERING.getCode())
                        .toStatus(OrderStatus.COMPLETED.getCode())
                        .remark("配送任务全部到达终态，过程聚合完成")
                        .build(),
                () -> lambdaUpdate()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getStatus, OrderStatus.DELIVERING.getCode())
                        .set(OrderInfo::getStatus, OrderStatus.COMPLETED.getCode())
                        .update());
        if (updated) {
            log.info("订单 {} 配送任务全部到达终态，自动完成", order.getOrderNo());
        }
        return updated;
    }

    @Override
    public void completeOrder(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        processTransitionExecutor.require(
                TransitionSpec.builder()
                        .scene(StateTransitions.SCENE_ORDER)
                        .action(StateTransitions.ACTION_COMPLETE)
                        .sceneText("订单")
                        .entityType("order_info")
                        .entityId(id)
                        .bizNo(order.getOrderNo())
                        .fromStatus(order.getStatus())
                        .toStatus(OrderStatus.COMPLETED.getCode())
                        .conflictMessage("订单状态已变更，请刷新后重试")
                        .remark("管理端手动完成订单")
                        .build(),
                () -> lambdaUpdate()
                        .eq(OrderInfo::getId, id)
                        .eq(OrderInfo::getStatus, OrderStatus.DELIVERING.getCode())
                        .set(OrderInfo::getStatus, OrderStatus.COMPLETED.getCode())
                        .update());
    }

    // ==================== 内部工具 ====================

    /** 作废某订单全部待支付流水（取消/重新发起支付/超时取消共用） */
    private void voidPendingRecords(Long orderId, String remark) {
        List<PaymentRecord> pendings = paymentRecordMapper.selectList(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId, orderId)
                        .eq(PaymentRecord::getStatus, 1));
        for (PaymentRecord pending : pendings) {
            pending.setStatus(3);
            pending.setRemark(remark);
            paymentRecordMapper.updateById(pending);
        }
    }

    private OrderInfo getOrder(Long id) {
        OrderInfo order = getById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    /** 明细转配额扣减项（按品种） */
    private List<QuotaDeductItem> toQuotaItems(List<OrderItem> items) {
        return items.stream()
                .map(i -> new QuotaDeductItem(i.getProductId(), i.getQuantity()))
                .collect(Collectors.toList());
    }

    /**
     * 校验当前用户是否有权访问该订单：
     * 家长仅能访问自己绑定学生的订单，班主任仅能访问本班订单；
     * 管理员与无登录上下文的内部流程（定时续订等）不限制。
     */
    private void checkOrderAccess(OrderInfo order) {
        DataScope scope = dataScopeResolver.resolveQuietly();
        if (scope.isAll()) {
            return;
        }
        if (scope.getStudentId() != null) {
            if (!scope.getStudentId().equals(order.getStudentId())) {
                throw new BusinessException(403, "无权访问该订单");
            }
        } else if (scope.getClassId() != null) {
            if (!scope.getClassId().equals(order.getClassId())) {
                throw new BusinessException(403, "无权访问该订单");
            }
        }
    }

    /**
     * 校验下单数据范围：家长仅能为自己绑定的学生下单，班主任仅能为本班学生下单
     */
    private void checkCreateOrderScope(CreateOrderRequest request) {
        DataScope scope = dataScopeResolver.resolve();
        if (scope.isAll()) {
            return;
        }
        if (scope.getStudentId() != null) {
            if (!scope.getStudentId().equals(request.getStudentId())) {
                throw new BusinessException(403, "只能为自己的孩子下单");
            }
            return;
        }
        if (scope.getClassId() != null) {
            Student student = studentMapper.selectById(request.getStudentId());
            if (student == null || !scope.getClassId().equals(student.getClassId())) {
                throw new BusinessException(403, "只能为本班学生下单");
            }
        }
    }

    private Long currentUserId() {
        String username = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(401, "未登录");
        }
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        return user == null ? null : user.getId();
    }

    private String generateOrderNo() {
        return nextNo("MO");
    }

    private String statusText(Integer status) {
        for (OrderStatus s : OrderStatus.values()) {
            if (s.getCode().equals(status)) {
                return s.getDesc();
            }
        }
        return "未知";
    }

    /**
     * 批量转换：一次查出学生/班级/套餐/下单人，避免 N+1
     */
    private List<OrderVO> convert(List<OrderInfo> orders) {
        if (CollectionUtils.isEmpty(orders)) {
            return Collections.emptyList();
        }
        Set<Long> studentIds = orders.stream().map(OrderInfo::getStudentId).collect(Collectors.toSet());
        Set<Long> userIds = orders.stream().map(OrderInfo::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> classIds = orders.stream().map(OrderInfo::getClassId).collect(Collectors.toSet());
        Set<Long> packageIds = orders.stream().map(OrderInfo::getPackageId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Student> studentMap = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<Long, ClassInfo> classMap = classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));
        Map<Long, MealPackage> packageMap = packageIds.isEmpty() ? Collections.emptyMap()
                : mealPackageMapper.selectBatchIds(packageIds).stream()
                .collect(Collectors.toMap(MealPackage::getId, Function.identity()));

        return orders.stream().map(order -> {
            Student s = studentMap.get(order.getStudentId());
            SysUser u = order.getUserId() == null ? null : userMap.get(order.getUserId());
            ClassInfo c = classMap.get(order.getClassId());
            MealPackage p = order.getPackageId() == null ? null : packageMap.get(order.getPackageId());
            return OrderVO.from(order,
                    s == null ? null : s.getStudentName(),
                    u == null ? null : u.getRealName(),
                    c == null ? null : c.getClassName(),
                    p == null ? null : p.getPackageName(),
                    statusText(order.getStatus()));
        }).collect(Collectors.toList());
    }
}
