package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.entity.ProductBatch;
import com.milk.order.module.product.vo.BatchTraceVO;

import java.util.List;

/**
 * 奶品批次服务（批次追溯钩子，见 {@link ProductBatch}）
 *
 * <p>日常业务不依赖本服务：备货按计划、任务展开/扣减/台账全部照旧，批次只是配额池上的可选标注。
 * 它的唯一职责是维护批次档案与提供召回反查。</p>
 */
public interface ProductBatchService extends IService<ProductBatch> {

    /** 批次列表（可按品种/状态筛选，按到货日期倒序） */
    List<ProductBatch> listBatches(Long productId, Integer status);

    /** 新增批次（批号唯一，冲突翻译为「批号已存在」） */
    void createBatch(ProductBatch batch);

    /** 修改批次 */
    void updateBatch(ProductBatch batch);

    /** 删除批次（逻辑删除；已标注的配额池保留批号字符串，反查不受影响） */
    void deleteBatch(Long id);

    /**
     * 批次召回反查：批号 → 关联配额池（日期×品种）→ 扣减台账 → 订单/任务/学生。
     *
     * <p>以**配额池上的批号标注**为入口，不要求先建批次档案——事故当场可直接用批号反查，
     * 档案只提供生产日期/到货日期等元信息。</p>
     */
    BatchTraceVO trace(String batchNo);
}
