package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.mapper.MealPackageMapper;
import com.milk.order.module.product.service.MealPackageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MealPackageServiceImpl extends ServiceImpl<MealPackageMapper, MealPackage> implements MealPackageService {

    @Override
    public List<MealPackage> listOrdered() {
        return lambdaQuery()
                .orderByDesc(MealPackage::getStatus)
                .orderByAsc(MealPackage::getSort)
                .orderByAsc(MealPackage::getId)
                .list();
    }

    @Override
    public void createPackage(MealPackage mealPackage) {
        validate(mealPackage);
        if (mealPackage.getStatus() == null) {
            mealPackage.setStatus(1);
        }
        if (mealPackage.getSort() == null) {
            mealPackage.setSort(0);
        }
        save(mealPackage);
    }

    @Override
    public void updatePackage(MealPackage mealPackage) {
        if (mealPackage.getId() == null) {
            throw new BusinessException("套餐ID不能为空");
        }
        MealPackage exists = getById(mealPackage.getId());
        if (exists == null) {
            throw new BusinessException("套餐不存在");
        }
        validate(mealPackage);
        updateById(mealPackage);
    }

    @Override
    public void removePackage(Long id) {
        MealPackage exists = getById(id);
        if (exists == null) {
            throw new BusinessException("套餐不存在");
        }
        removeById(id);
    }

    private void validate(MealPackage p) {
        if (!StringUtils.hasText(p.getPackageName())) {
            throw new BusinessException("套餐名称不能为空");
        }
        if (p.getPackageType() == null || (p.getPackageType() != 1 && p.getPackageType() != 2)) {
            throw new BusinessException("套餐类型非法（1-按月，2-按学期）");
        }
        if (p.getDiscountPrice() == null || p.getDiscountPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("套餐优惠价不合法");
        }
    }
}
