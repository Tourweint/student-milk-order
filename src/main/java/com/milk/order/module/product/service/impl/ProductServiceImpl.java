package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.entity.Inventory;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.InventoryService;
import com.milk.order.module.product.service.ProductCategoryService;
import com.milk.order.module.product.service.ProductService;
import com.milk.order.module.product.vo.ProductVO;
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
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final ProductCategoryService categoryService;
    private final InventoryService inventoryService;

    @Override
    public IPage<ProductVO> pageProducts(Long pageNum, Long pageSize, Long categoryId, String keyword) {
        Page<Product> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        IPage<Product> productPage = lambdaQuery()
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(Product::getProductName, keyword)
                        .or().like(Product::getFlavor, keyword))
                .orderByAsc(Product::getSort)
                .orderByAsc(Product::getId)
                .page(page);

        List<Product> products = productPage.getRecords();
        List<ProductVO> voList = convert(products);

        Page<ProductVO> result = new Page<>(productPage.getCurrent(), productPage.getSize(), productPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createProduct(Product product) {
        validate(product);
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        if (product.getSort() == null) {
            product.setSort(0);
        }
        save(product);
        // 自动初始化一条 0 库存
        inventoryService.ensureInventory(product.getId());
    }

    @Override
    public void updateProduct(Product product) {
        if (product.getId() == null) {
            throw new BusinessException("奶品ID不能为空");
        }
        Product exists = getById(product.getId());
        if (exists == null) {
            throw new BusinessException("奶品不存在");
        }
        validate(product);
        updateById(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeProduct(Long id) {
        Product exists = getById(id);
        if (exists == null) {
            throw new BusinessException("奶品不存在");
        }
        // 逻辑删奶品，同时清理其库存记录（流水保留作历史统计）
        inventoryService.lambdaUpdate().eq(Inventory::getProductId, id).remove();
        removeById(id);
    }

    private void validate(Product product) {
        if (!StringUtils.hasText(product.getProductName())) {
            throw new BusinessException("奶品名称不能为空");
        }
        if (product.getCategoryId() == null) {
            throw new BusinessException("请选择品类");
        }
        ProductCategory category = categoryService.getById(product.getCategoryId());
        if (category == null) {
            throw new BusinessException("所选品类不存在");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("奶品单价不合法");
        }
    }

    private List<ProductVO> convert(List<Product> products) {
        if (CollectionUtils.isEmpty(products)) {
            return Collections.emptyList();
        }
        Set<Long> categoryIds = products.stream().map(Product::getCategoryId).collect(Collectors.toSet());
        Map<Long, ProductCategory> categoryMap = categoryService.listByIds(categoryIds).stream()
                .collect(Collectors.toMap(ProductCategory::getId, Function.identity()));

        Set<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toSet());
        Map<Long, Integer> quantityMap = inventoryService.lambdaQuery()
                .in(Inventory::getProductId, productIds).list().stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getQuantity, (a, b) -> a));

        return products.stream().map(p -> {
            ProductCategory c = categoryMap.get(p.getCategoryId());
            return ProductVO.from(p, c == null ? null : c.getCategoryName(), quantityMap.get(p.getId()));
        }).collect(Collectors.toList());
    }
}
