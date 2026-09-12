package com.milk.order.module.stats.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.ClassInfoMapper;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.nutrition.entity.NutritionIntake;
import com.milk.order.module.nutrition.mapper.NutritionIntakeMapper;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.module.order.mapper.OrderItemMapper;

import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.product.mapper.ProductCategoryMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.stats.service.StatsService;
import com.milk.order.module.stats.vo.ClassRankingVO;
import com.milk.order.module.stats.vo.CoverageVO;
import com.milk.order.module.stats.vo.DashboardVO;
import com.milk.order.module.stats.vo.TrendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final StudentMapper studentMapper;
    private final ClassInfoMapper classInfoMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final DailyQuotaService dailyQuotaService;
    private final NutritionIntakeMapper nutritionIntakeMapper;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ==================== 仪表盘总览 ====================

    @Override
    public DashboardVO dashboard() {
        DashboardVO vo = new DashboardVO();

        // 订单总数
        vo.setTotalOrders(orderInfoMapper.selectCount(null));

        // 在订学生数（状态为已支付/配送中/已完成的订单关联学生去重）
        List<OrderInfo> activeOrders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getStatus, 2, 3, 4));
        vo.setActiveStudents(activeOrders.stream().map(OrderInfo::getStudentId).distinct().count());

        // 本月销售额（已支付订单，pay_time 在本月）
        YearMonth currentMonth = YearMonth.now();
        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = currentMonth.atEndOfMonth().atTime(23, 59, 59);
        List<OrderInfo> paidThisMonth = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getStatus, 2)
                        .ge(OrderInfo::getPayTime, monthStart)
                        .le(OrderInfo::getPayTime, monthEnd));
        BigDecimal monthlySales = paidThisMonth.stream()
                .map(o -> o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setMonthlySales(monthlySales);

        // 今日机动配额剩余（仅单日零散订购占用）
        vo.setTodayQuotaRemaining((long) dailyQuotaService.remaining(java.time.LocalDate.now()));

        return vo;
    }

    // ==================== 订单趋势 ====================

    @Override
    public TrendVO orderTrend(String type, String startDate, String endDate) {
        TrendVO vo = new TrendVO();
        List<String> dates = new ArrayList<>();
        List<Long> orderCounts = new ArrayList<>();
        List<BigDecimal> sales = new ArrayList<>();

        boolean isMonth = "month".equalsIgnoreCase(type);
        LocalDate start, end;

        if (StringUtils.hasText(startDate) && StringUtils.hasText(endDate)) {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } else if (isMonth) {
            end = LocalDate.now();
            start = end.minusMonths(11).withDayOfMonth(1);
        } else {
            end = LocalDate.now();
            start = end.minusDays(29);
        }

        // 查已完成/已支付/配送中的订单
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .in(OrderInfo::getStatus, 2, 3, 4)
                        .ge(OrderInfo::getCreateTime, start.atStartOfDay())
                        .le(OrderInfo::getCreateTime, end.atTime(23, 59, 59)));

        // 按日期/月份分组
        Map<String, List<OrderInfo>> grouped = orders.stream()
                .collect(Collectors.groupingBy(o ->
                        isMonth ? o.getCreateTime().format(MONTH_FMT)
                                : o.getCreateTime().toLocalDate().format(DAY_FMT)));

        // 生成连续日期/月份标签
        if (isMonth) {
            YearMonth cur = YearMonth.from(start);
            YearMonth last = YearMonth.from(end);
            while (!cur.isAfter(last)) {
                String label = cur.format(MONTH_FMT);
                dates.add(label);
                List<OrderInfo> list = grouped.getOrDefault(label, Collections.emptyList());
                orderCounts.add((long) list.size());
                sales.add(list.stream().map(o -> o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                cur = cur.plusMonths(1);
            }
        } else {
            LocalDate cur = start;
            while (!cur.isAfter(end)) {
                String label = cur.format(DAY_FMT);
                dates.add(label);
                List<OrderInfo> list = grouped.getOrDefault(label, Collections.emptyList());
                orderCounts.add((long) list.size());
                sales.add(list.stream().map(o -> o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                cur = cur.plusDays(1);
            }
        }

        vo.setDates(dates);
        vo.setOrderCounts(orderCounts);
        vo.setSales(sales);
        return vo;
    }

    // ==================== 奶品品类占比 ====================

    @Override
    public List<Map<String, Object>> orderCategory() {
        // 查已支付/配送中/已完成订单的明细
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getStatus, 2, 3, 4));
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> orderIds = orders.stream().map(OrderInfo::getId).collect(Collectors.toSet());
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds));

        // 按 productId 汇总数量
        Map<Long, Integer> productQty = items.stream()
                .collect(Collectors.groupingBy(OrderItem::getProductId,
                        Collectors.summingInt(OrderItem::getQuantity)));

        // 查奶品和品类
        Set<Long> productIds = productQty.keySet();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        Set<Long> categoryIds = productMap.values().stream()
                .map(Product::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ProductCategory> categoryMap = categoryIds.isEmpty() ? Collections.emptyMap()
                : productCategoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(ProductCategory::getId, c -> c));

        // 按品类汇总
        Map<String, Integer> categoryQty = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : productQty.entrySet()) {
            Product p = productMap.get(entry.getKey());
            String categoryName = "未分类";
            if (p != null && p.getCategoryId() != null) {
                ProductCategory cat = categoryMap.get(p.getCategoryId());
                if (cat != null) categoryName = cat.getCategoryName();
            }
            categoryQty.merge(categoryName, entry.getValue(), Integer::sum);
        }

        return categoryQty.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", e.getValue());
                    return m;
                }).collect(Collectors.toList());
    }

    // ==================== 班级订购排行榜 ====================

    @Override
    public List<ClassRankingVO> classRanking(Integer limit) {
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getStatus, 2, 3, 4));
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }

        // 按班级分组
        Map<Long, List<OrderInfo>> byClass = orders.stream()
                .filter(o -> o.getClassId() != null)
                .collect(Collectors.groupingBy(OrderInfo::getClassId));

        Set<Long> classIds = byClass.keySet();
        Map<Long, ClassInfo> classMap = classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, c -> c));

        return byClass.entrySet().stream().map(e -> {
            ClassRankingVO vo = new ClassRankingVO();
            ClassInfo c = classMap.get(e.getKey());
            vo.setClassId(e.getKey());
            vo.setClassName(c == null ? "未知班级" : c.getClassName());
            vo.setOrderCount((long) e.getValue().size());
            vo.setSales(e.getValue().stream()
                    .map(o -> o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            return vo;
        }).sorted(Comparator.comparing(ClassRankingVO::getOrderCount).reversed())
                .limit(limit == null ? 10 : limit)
                .collect(Collectors.toList());
    }

    // ==================== 营养摄入仪表盘 ====================

    @Override
    public Map<String, Object> nutritionDashboard(Long classId) {
        LambdaQueryWrapper<NutritionIntake> wrapper = new LambdaQueryWrapper<>();
        if (classId != null) {
            // 通过学生关联班级
            List<Student> students = studentMapper.selectList(
                    new LambdaQueryWrapper<Student>().eq(Student::getClassId, classId));
            Set<Long> studentIds = students.stream().map(Student::getId).collect(Collectors.toSet());
            if (studentIds.isEmpty()) {
                return emptyNutrition();
            }
            wrapper.in(NutritionIntake::getStudentId, studentIds);
        }
        List<NutritionIntake> intakes = nutritionIntakeMapper.selectList(wrapper);
        if (intakes.isEmpty()) {
            return emptyNutrition();
        }

        BigDecimal totalEnergy = sum(intakes, NutritionIntake::getEnergy);
        BigDecimal totalProtein = sum(intakes, NutritionIntake::getProtein);
        BigDecimal totalCalcium = sum(intakes, NutritionIntake::getCalcium);
        long days = intakes.stream().map(NutritionIntake::getIntakeDate).distinct().count();
        long studentCount = intakes.stream().map(NutritionIntake::getStudentId).distinct().count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEnergy", totalEnergy);
        result.put("totalProtein", totalProtein);
        result.put("totalCalcium", totalCalcium);
        result.put("totalMl", intakes.stream().mapToInt(NutritionIntake::getQuantity).sum());
        result.put("days", days);
        result.put("studentCount", studentCount);
        // 平均每日每生摄入
        if (days > 0 && studentCount > 0) {
            BigDecimal divisor = BigDecimal.valueOf(days * studentCount);
            result.put("avgDailyEnergy", totalEnergy.divide(divisor, 1, RoundingMode.HALF_UP));
            result.put("avgDailyProtein", totalProtein.divide(divisor, 1, RoundingMode.HALF_UP));
            result.put("avgDailyCalcium", totalCalcium.divide(divisor, 1, RoundingMode.HALF_UP));
        } else {
            result.put("avgDailyEnergy", BigDecimal.ZERO);
            result.put("avgDailyProtein", BigDecimal.ZERO);
            result.put("avgDailyCalcium", BigDecimal.ZERO);
        }
        return result;
    }

    private Map<String, Object> emptyNutrition() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEnergy", BigDecimal.ZERO);
        m.put("totalProtein", BigDecimal.ZERO);
        m.put("totalCalcium", BigDecimal.ZERO);
        m.put("totalMl", 0);
        m.put("days", 0);
        m.put("studentCount", 0);
        m.put("avgDailyEnergy", BigDecimal.ZERO);
        m.put("avgDailyProtein", BigDecimal.ZERO);
        m.put("avgDailyCalcium", BigDecimal.ZERO);
        return m;
    }

    // ==================== 学生喝奶覆盖率 ====================

    @Override
    public CoverageVO coverage() {
        CoverageVO vo = new CoverageVO();

        List<Student> allStudents = studentMapper.selectList(null);
        long totalStudents = allStudents.size();
        vo.setTotalStudents(totalStudents);

        // 在订学生
        List<OrderInfo> activeOrders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getStatus, 2, 3, 4));
        Set<Long> activeStudentIds = activeOrders.stream()
                .map(OrderInfo::getStudentId).collect(Collectors.toSet());
        long activeStudents = activeStudentIds.size();
        vo.setActiveStudents(activeStudents);
        vo.setTotalRate(totalStudents > 0
                ? BigDecimal.valueOf(activeStudents * 100.0 / totalStudents)
                        .setScale(1, RoundingMode.HALF_UP).doubleValue()
                : 0.0);

        // 各班级覆盖率
        Map<Long, List<Student>> studentsByClass = allStudents.stream()
                .collect(Collectors.groupingBy(Student::getClassId));
        Set<Long> classIds = studentsByClass.keySet();
        Map<Long, ClassInfo> classMap = classInfoMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, c -> c));

        List<CoverageVO.ClassCoverage> classList = new ArrayList<>();
        for (Map.Entry<Long, List<Student>> entry : studentsByClass.entrySet()) {
            CoverageVO.ClassCoverage cc = new CoverageVO.ClassCoverage();
            ClassInfo c = classMap.get(entry.getKey());
            cc.setClassId(entry.getKey());
            cc.setClassName(c == null ? "未知" : c.getClassName());
            cc.setTotalStudents((long) entry.getValue().size());
            long classActive = entry.getValue().stream()
                    .filter(s -> activeStudentIds.contains(s.getId())).count();
            cc.setActiveStudents(classActive);
            cc.setRate(entry.getValue().size() > 0
                    ? BigDecimal.valueOf(classActive * 100.0 / entry.getValue().size())
                            .setScale(1, RoundingMode.HALF_UP).doubleValue()
                    : 0.0);
            classList.add(cc);
        }
        classList.sort(Comparator.comparing(CoverageVO.ClassCoverage::getRate).reversed());
        vo.setClassList(classList);

        return vo;
    }

    // ==================== 工具 ====================

    private BigDecimal sum(List<NutritionIntake> list, java.util.function.Function<NutritionIntake, BigDecimal> getter) {
        return list.stream().map(getter).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
