package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.dto.PackageSaveRequest;
import com.milk.order.module.product.entity.MealPackage;
import com.milk.order.module.product.vo.MealPackageDetailVO;

import java.util.List;

public interface MealPackageService extends IService<MealPackage> {

    /** 全部套餐（上架优先，按 sort 升序） */
    List<MealPackage> listOrdered();

    /** 套餐详情（含固定配送明细，回填奶品名/规格）；不存在时返回 null */
    MealPackageDetailVO getPackageDetail(Long id);

    /** 新增套餐（校验名称、类型、价格与固定明细） */
    void createPackage(PackageSaveRequest request);

    /** 修改套餐（固定明细整体替换） */
    void updatePackage(PackageSaveRequest request);

    /** 删除套餐（同时清理其固定明细） */
    void removePackage(Long id);
}
