package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.entity.ProductCategory;

import java.util.List;

public interface ProductCategoryService extends IService<ProductCategory> {

    /** 全部品类（按 sort 升序，只返回上架） */
    List<ProductCategory> listOrdered();

    /** 新增品类（名称唯一校验） */
    void createCategory(ProductCategory category);

    /** 修改品类 */
    void updateCategory(ProductCategory category);

    /** 删除品类（其下有奶品时禁止） */
    void removeCategory(Long id);
}
