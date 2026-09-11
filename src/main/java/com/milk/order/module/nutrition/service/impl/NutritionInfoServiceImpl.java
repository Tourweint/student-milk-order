package com.milk.order.module.nutrition.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.nutrition.dto.NutritionInfoRequest;
import com.milk.order.module.nutrition.entity.NutritionInfo;
import com.milk.order.module.nutrition.entity.NutritionIntake;
import com.milk.order.module.nutrition.mapper.NutritionInfoMapper;
import com.milk.order.module.nutrition.mapper.NutritionIntakeMapper;
import com.milk.order.module.nutrition.service.NutritionInfoService;
import com.milk.order.module.nutrition.vo.NutritionInfoVO;
import com.milk.order.module.nutrition.vo.NutritionIntakeVO;
import com.milk.order.module.nutrition.vo.NutritionSummaryVO;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NutritionInfoServiceImpl extends ServiceImpl<NutritionInfoMapper, NutritionInfo> implements NutritionInfoService {

    private final NutritionIntakeMapper nutritionIntakeMapper;
    private final StudentMapper studentMapper;
    private final ProductMapper productMapper;

    // ==================== 营养成分管理 ====================

    @Override
    public List<NutritionInfoVO> listWithProduct() {
        List<NutritionInfo> list = list();
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> productIds = list.stream().map(NutritionInfo::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return list.stream().map(info -> {
            Product p = productMap.get(info.getProductId());
            return NutritionInfoVO.from(info,
                    p == null ? null : p.getProductName(),
                    p == null ? null : p.getSpec());
        }).collect(Collectors.toList());
    }

    @Override
    public NutritionInfoVO getByProductId(Long productId) {
        NutritionInfo info = getOne(new LambdaQueryWrapper<NutritionInfo>()
                .eq(NutritionInfo::getProductId, productId));
        if (info == null) {
            return null;
        }
        Product p = productMapper.selectById(productId);
        return NutritionInfoVO.from(info,
                p == null ? null : p.getProductName(),
                p == null ? null : p.getSpec());
    }

    @Override
    public void saveOrUpdateByProduct(NutritionInfoRequest request) {
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new BusinessException("奶品不存在");
        }
        NutritionInfo existing = getOne(new LambdaQueryWrapper<NutritionInfo>()
                .eq(NutritionInfo::getProductId, request.getProductId()));
        if (existing != null) {
            existing.setEnergy(request.getEnergy());
            existing.setProtein(request.getProtein());
            existing.setFat(request.getFat());
            existing.setCarbohydrate(request.getCarbohydrate());
            existing.setCalcium(request.getCalcium());
            existing.setSodium(request.getSodium());
            existing.setRemark(request.getRemark());
            updateById(existing);
        } else {
            NutritionInfo info = new NutritionInfo();
            info.setProductId(request.getProductId());
            info.setEnergy(request.getEnergy());
            info.setProtein(request.getProtein());
            info.setFat(request.getFat());
            info.setCarbohydrate(request.getCarbohydrate());
            info.setCalcium(request.getCalcium());
            info.setSodium(request.getSodium());
            info.setRemark(request.getRemark());
            save(info);
        }
    }

    // ==================== 营养摄入查询 ====================

    @Override
    public IPage<NutritionIntakeVO> pageIntakes(Long pageNum, Long pageSize, Long studentId,
                                                  String startDate, String endDate) {
        Page<NutritionIntake> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<NutritionIntake> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(studentId != null, NutritionIntake::getStudentId, studentId);
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(NutritionIntake::getIntakeDate, startDate);
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(NutritionIntake::getIntakeDate, endDate);
        }
        wrapper.orderByDesc(NutritionIntake::getIntakeDate).orderByDesc(NutritionIntake::getId);

        IPage<NutritionIntake> intakePage = nutritionIntakeMapper.selectPage(page, wrapper);
        List<NutritionIntakeVO> voList = convertIntakes(intakePage.getRecords());

        Page<NutritionIntakeVO> result = new Page<>(intakePage.getCurrent(), intakePage.getSize(), intakePage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public List<NutritionSummaryVO> summaryByStudent(Long studentId, String startDate, String endDate) {
        LambdaQueryWrapper<NutritionIntake> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NutritionIntake::getStudentId, studentId);
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(NutritionIntake::getIntakeDate, startDate);
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(NutritionIntake::getIntakeDate, endDate);
        }
        wrapper.orderByAsc(NutritionIntake::getIntakeDate);
        List<NutritionIntake> records = nutritionIntakeMapper.selectList(wrapper);
        if (records.isEmpty()) {
            return Collections.emptyList();
        }

        // 按日期分组汇总
        Map<LocalDate, List<NutritionIntake>> grouped = records.stream()
                .collect(Collectors.groupingBy(NutritionIntake::getIntakeDate));

        return grouped.entrySet().stream().map(entry -> {
            LocalDate date = entry.getKey();
            List<NutritionIntake> items = entry.getValue();
            NutritionSummaryVO vo = new NutritionSummaryVO();
            vo.setIntakeDate(date);
            vo.setTotalMl(items.stream().mapToInt(NutritionIntake::getQuantity).sum());
            vo.setTotalEnergy(sum(items, NutritionIntake::getEnergy));
            vo.setTotalProtein(sum(items, NutritionIntake::getProtein));
            vo.setTotalFat(sum(items, NutritionIntake::getFat));
            vo.setTotalCalcium(sum(items, NutritionIntake::getCalcium));
            vo.setProductCount((int) items.stream().map(NutritionIntake::getProductId).distinct().count());
            return vo;
        }).sorted(Comparator.comparing(NutritionSummaryVO::getIntakeDate)).collect(Collectors.toList());
    }

    // ==================== 内部工具 ====================

    private BigDecimal sum(List<NutritionIntake> items, Function<NutritionIntake, BigDecimal> getter) {
        return items.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<NutritionIntakeVO> convertIntakes(List<NutritionIntake> records) {
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> studentIds = records.stream().map(NutritionIntake::getStudentId).collect(Collectors.toSet());
        Set<Long> productIds = records.stream().map(NutritionIntake::getProductId).collect(Collectors.toSet());

        Map<Long, Student> studentMap = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return records.stream().map(r -> {
            Student s = studentMap.get(r.getStudentId());
            Product p = productMap.get(r.getProductId());
            return NutritionIntakeVO.from(r,
                    s == null ? null : s.getStudentName(),
                    p == null ? null : p.getProductName());
        }).collect(Collectors.toList());
    }
}
