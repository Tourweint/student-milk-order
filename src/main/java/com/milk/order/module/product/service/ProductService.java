package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.vo.ProductVO;

public interface ProductService extends IService<Product> {

    /** 分页查询奶品（品类 + 关键词筛选），附带品类名与当前库存 */
    IPage<ProductVO> pageProducts(Long pageNum, Long pageSize, Long categoryId, String keyword);

    /** 新增奶品（校验品类、价格，并自动初始化 0 库存记录） */
    void createProduct(Product product);

    /** 修改奶品 */
    void updateProduct(Product product);

    /** 删除奶品（同时清理其库存记录，流水保留） */
    void removeProduct(Long id);
}
