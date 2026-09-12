package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.dto.PackageSaveRequest;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.entity.MealPackageItem;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.MealPackageItemMapper;
import com.milk.order.module.product.mapper.MealPackageMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.MealPackageService;
import com.milk.order.module.product.vo.MealPackageDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MealPackageServiceImpl extends ServiceImpl<MealPackageMapper, MealPackage> implements MealPackageService {

    private final MealPackageItemMapper mealPackageItemMapper;
    private final ProductMapper productMapper;

    @Override
    public List<MealPackage> listOrdered() {
        return lambdaQuery()
                .orderByDesc(MealPackage::getStatus)
                .orderByAsc(MealPackage::getSort)
                .orderByAsc(MealPackage::getId)
                .list();
    }

    @Override
    public MealPackageDetailVO getPackageDetail(Long id) {
        MealPackage pkg = getById(id);
        if (pkg == null) {
            return null;
        }
        List<MealPackageItem> items = listItems(id);
        Map<Long, Product> productMap = items.isEmpty() ? Collections.emptyMap()
                : productMapper.selectBatchIds(items.stream().map(MealPackageItem::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        MealPackageDetailVO vo = new MealPackageDetailVO();
        vo.setId(pkg.getId());
        vo.setPackageName(pkg.getPackageName());
        vo.setPackageType(pkg.getPackageType());
        vo.setDescription(pkg.getDescription());
        vo.setOriginalPrice(pkg.getOriginalPrice());
        vo.setDiscountPrice(pkg.getDiscountPrice());
        vo.setStartDate(pkg.getStartDate());
        vo.setEndDate(pkg.getEndDate());
        vo.setStatus(pkg.getStatus());
        vo.setSort(pkg.getSort());
        vo.setItems(items.stream().map(item -> {
            Product p = productMap.get(item.getProductId());
            MealPackageDetailVO.PackageItemVO itemVO = new MealPackageDetailVO.PackageItemVO();
            itemVO.setProductId(item.getProductId());
            itemVO.setProductName(p == null ? null : p.getProductName());
            itemVO.setSpec(p == null ? null : p.getSpec());
            itemVO.setQuantity(item.getQuantity());
            return itemVO;
        }).collect(Collectors.toList()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPackage(PackageSaveRequest request) {
        MealPackage pkg = new MealPackage();
        applyRequest(pkg, request);
        validate(pkg);
        validateItems(request);
        if (pkg.getStatus() == null) {
            pkg.setStatus(1);
        }
        if (pkg.getSort() == null) {
            pkg.setSort(0);
        }
        save(pkg);
        saveItems(pkg.getId(), request.getItems());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePackage(PackageSaveRequest request) {
        if (request.getId() == null) {
            throw new BusinessException("套餐ID不能为空");
        }
        MealPackage exists = getById(request.getId());
        if (exists == null) {
            throw new BusinessException("套餐不存在");
        }
        applyRequest(exists, request);
        validate(exists);
        validateItems(request);
        updateById(exists);
        // 固定明细整体替换
        mealPackageItemMapper.delete(new LambdaQueryWrapper<MealPackageItem>()
                .eq(MealPackageItem::getPackageId, request.getId()));
        saveItems(request.getId(), request.getItems());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removePackage(Long id) {
        MealPackage exists = getById(id);
        if (exists == null) {
            throw new BusinessException("套餐不存在");
        }
        mealPackageItemMapper.delete(new LambdaQueryWrapper<MealPackageItem>()
                .eq(MealPackageItem::getPackageId, id));
        removeById(id);
    }

    // ==================== 内部工具 ====================

    private List<MealPackageItem> listItems(Long packageId) {
        return mealPackageItemMapper.selectList(new LambdaQueryWrapper<MealPackageItem>()
                .eq(MealPackageItem::getPackageId, packageId)
                .orderByAsc(MealPackageItem::getId));
    }

    private void applyRequest(MealPackage pkg, PackageSaveRequest request) {
        pkg.setPackageName(request.getPackageName());
        pkg.setPackageType(request.getPackageType());
        pkg.setDescription(request.getDescription());
        pkg.setOriginalPrice(request.getOriginalPrice());
        pkg.setDiscountPrice(request.getDiscountPrice());
        pkg.setStartDate(request.getStartDate());
        pkg.setEndDate(request.getEndDate());
        pkg.setStatus(request.getStatus());
        pkg.setSort(request.getSort());
    }

    private void validate(MealPackage p) {
        if (!StringUtils.hasText(p.getPackageName())) {
            throw new BusinessException("套餐名称不能为空");
        }
        // 业务规则：仅保留学期套餐（全校统一预约定制），月度套餐已下线
        p.setPackageType(2);
        if (p.getDiscountPrice() == null || p.getDiscountPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("套餐优惠价不合法");
        }
    }

    /** 套餐必须配置固定明细，且奶品须存在 */
    private void validateItems(PackageSaveRequest request) {
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new BusinessException("请配置套餐固定配送明细（至少一种奶品）");
        }
        Set<Long> productIds = request.getItems().stream()
                .map(PackageSaveRequest.Item::getProductId).collect(Collectors.toSet());
        if (productMapper.selectBatchIds(productIds).size() != productIds.size()) {
            throw new BusinessException("套餐明细中存在不存在的奶品");
        }
    }

    private void saveItems(Long packageId, List<PackageSaveRequest.Item> items) {
        for (PackageSaveRequest.Item item : items) {
            MealPackageItem entity = new MealPackageItem();
            entity.setPackageId(packageId);
            entity.setProductId(item.getProductId());
            entity.setQuantity(item.getQuantity());
            mealPackageItemMapper.insert(entity);
        }
    }
}
