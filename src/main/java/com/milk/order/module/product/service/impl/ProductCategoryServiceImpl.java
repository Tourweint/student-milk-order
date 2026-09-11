package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.mapper.ProductCategoryMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductCategoryServiceImpl
        extends ServiceImpl<ProductCategoryMapper, ProductCategory> implements ProductCategoryService {

    /** 删除品类前统计其下奶品，避免与 ProductService 循环依赖 */
    private final ProductMapper productMapper;

    @Override
    public List<ProductCategory> listOrdered() {
        return lambdaQuery()
                .eq(ProductCategory::getStatus, 1)
                .orderByAsc(ProductCategory::getSort)
                .orderByAsc(ProductCategory::getId)
                .list();
    }

    @Override
    public void createCategory(ProductCategory category) {
        validateName(category);
        boolean duplicated = lambdaQuery()
                .eq(ProductCategory::getCategoryName, category.getCategoryName())
                .count() > 0;
        if (duplicated) {
            throw new BusinessException("品类名称已存在");
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        if (category.getSort() == null) {
            category.setSort(0);
        }
        save(category);
    }

    @Override
    public void updateCategory(ProductCategory category) {
        if (category.getId() == null) {
            throw new BusinessException("品类ID不能为空");
        }
        ProductCategory exists = getById(category.getId());
        if (exists == null) {
            throw new BusinessException("品类不存在");
        }
        validateName(category);
        boolean duplicated = lambdaQuery()
                .eq(ProductCategory::getCategoryName, category.getCategoryName())
                .ne(ProductCategory::getId, category.getId())
                .count() > 0;
        if (duplicated) {
            throw new BusinessException("品类名称已存在");
        }
        updateById(category);
    }

    @Override
    public void removeCategory(Long id) {
        Long count = productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getCategoryId, id));
        if (count != null && count > 0) {
            throw new BusinessException("该品类下仍有奶品，无法删除");
        }
        removeById(id);
    }

    private void validateName(ProductCategory category) {
        if (!StringUtils.hasText(category.getCategoryName())) {
            throw new BusinessException("品类名称不能为空");
        }
    }
}
