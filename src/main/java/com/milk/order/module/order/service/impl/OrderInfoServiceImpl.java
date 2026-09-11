package com.milk.order.module.order.service.impl;

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
import com.milk.order.module.order.dto.CreateOrderRequest;
import com.milk.order.module.order.dto.OrderItemRequest;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.entity.PaymentRecord;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.mapper.OrderItemMapper;
import com.milk.order.module.order.mapper.PaymentRecordMapper;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.module.order.vo.OrderVO;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.MealPackageMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.InventoryService;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.mapper.SysUserMapper;
import com.milk.order.module.user.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    private final OrderItemMapper orderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final MealPackageMapper mealPackageMapper;
    private final ProductMapper productMapper;
    private final SysUserMapper sysUserMapper;
    private final InventoryService inventoryService;
    private final DataScopeResolver dataScopeResolver;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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
        // 3. 校验奶品并计算金额
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new BusinessException("订单明细不能为空");
        }
        Set<Long> productIds = request.getItems().stream()
                .map(OrderItemRequest::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItemRequest item : request.getItems()) {
            if (!productMap.containsKey(item.getProductId())) {
                throw new BusinessException("奶品不存在：id=" + item.getProductId());
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
            payAmount = pkg.getDiscountPrice();
        } else {
            totalAmount = BigDecimal.ZERO;
            for (OrderItemRequest item : request.getItems()) {
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
        String orderNo = generateOrderNo();

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
        for (OrderItemRequest item : request.getItems()) {
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

    // ==================== 模拟支付 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        if (!OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BusinessException("当前订单状态不允许支付");
        }
        // 1. 扣减库存（每个明细），库存不足会抛异常并回滚
        List<OrderItem> items = getOrderItems(id);
        for (OrderItem item : items) {
            inventoryService.deductForOrder(item.getProductId(), item.getQuantity(), id);
        }
        // 2. 写支付记录
        String transactionId = "MOCK" + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 9999);
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

        // 3. 更新订单状态
        order.setStatus(OrderStatus.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        order.setPayType(1);
        order.setTransactionId(transactionId);
        updateById(order);
    }

    // ==================== 退订 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long id, String reason) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        Integer status = order.getStatus();
        if (!OrderStatus.PENDING_PAYMENT.getCode().equals(status)
                && !OrderStatus.PAID.getCode().equals(status)) {
            throw new BusinessException("当前订单状态不允许退订（仅待支付/已支付可退）");
        }
        // 已支付的退订需要回库
        if (OrderStatus.PAID.getCode().equals(status)) {
            List<OrderItem> items = getOrderItems(id);
            for (OrderItem item : items) {
                inventoryService.restoreForOrder(item.getProductId(), item.getQuantity(), id);
            }
        }
        order.setStatus(OrderStatus.CANCELLED.getCode());
        order.setCancelTime(LocalDateTime.now());
        order.setCancelReason(StringUtils.hasText(reason) ? reason : "用户退订");
        updateById(order);
    }

    // ==================== 配送/完成 ====================

    @Override
    public void startDelivery(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        if (!OrderStatus.PAID.getCode().equals(order.getStatus())) {
            throw new BusinessException("仅已支付订单可开始配送");
        }
        order.setStatus(OrderStatus.DELIVERING.getCode());
        updateById(order);
    }

    @Override
    public void completeOrder(Long id) {
        OrderInfo order = getOrder(id);
        checkOrderAccess(order);
        if (!OrderStatus.DELIVERING.getCode().equals(order.getStatus())) {
            throw new BusinessException("仅配送中订单可完成");
        }
        order.setStatus(OrderStatus.COMPLETED.getCode());
        updateById(order);
    }

    // ==================== 内部工具 ====================

    private OrderInfo getOrder(Long id) {
        OrderInfo order = getById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
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
        return "MO" + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 9999);
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
