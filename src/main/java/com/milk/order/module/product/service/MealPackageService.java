package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.entity.MealPackage;

import java.util.List;

public interface MealPackageService extends IService<MealPackage> {

    /** 全部套餐（上架优先，按 sort 升序） */
    List<MealPackage> listOrdered();

    /** 新增套餐（校验名称、类型、价格） */
    void createPackage(MealPackage mealPackage);

    /** 修改套餐 */
    void updatePackage(MealPackage mealPackage);

    /** 删除套餐 */
    void removePackage(Long id);
}
