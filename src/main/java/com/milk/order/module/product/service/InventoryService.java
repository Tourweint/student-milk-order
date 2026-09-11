package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.dto.InventoryChangeRequest;
import com.milk.order.module.product.entity.Inventory;
import com.milk.order.module.product.vo.InventoryRecordVO;
import com.milk.order.module.product.vo.InventoryVO;

import java.util.List;

public interface InventoryService extends IService<Inventory> {

    /** 分页查询库存（附带奶品名/规格/品类名），keyword 匹配奶品名 */
    IPage<InventoryVO> pageInventory(Long pageNum, Long pageSize, String keyword);

    /** 库存预警列表（当前数量 ≤ 预警阈值） */
    List<InventoryVO> listWarning();

    /**
     * 库存变动（入库/出库/盘盈/盘亏）——核心方法：
     * 同一事务内更新库存余量并写入一条流水，保证账实一致
     */
    void changeStock(InventoryChangeRequest request);

    /** 流水分页查询（可按奶品、变动类型筛选），附带奶品名/操作人名 */
    IPage<InventoryRecordVO> pageRecords(Long pageNum, Long pageSize, Long productId, Integer changeType);

    /** 确保奶品存在一条库存记录（新增奶品时初始化 0 库存） */
    void ensureInventory(Long productId);

    /** 设置预警阈值与库位 */
    void updateThreshold(Long id, Integer warningThreshold, String warehouseLocation);

    /**
     * 订单出库扣减（供订单模块调用）：库存不足抛业务异常，同事务写流水
     */
    void deductForOrder(Long productId, Integer quantity, Long orderId);

    /**
     * 退订回库（供订单模块调用）：同事务写流水
     */
    void restoreForOrder(Long productId, Integer quantity, Long orderId);
}
