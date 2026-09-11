package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.enums.InventoryChangeType;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.dto.InventoryChangeRequest;
import com.milk.order.module.product.entity.Inventory;
import com.milk.order.module.product.entity.InventoryRecord;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.entity.ProductCategory;
import com.milk.order.module.product.mapper.InventoryMapper;
import com.milk.order.module.product.mapper.InventoryRecordMapper;
import com.milk.order.module.product.mapper.ProductCategoryMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.InventoryService;
import com.milk.order.module.product.vo.InventoryRecordVO;
import com.milk.order.module.product.vo.InventoryVO;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl extends ServiceImpl<InventoryMapper, Inventory> implements InventoryService {

    private final InventoryRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final SysUserService sysUserService;

    @Override
    public IPage<InventoryVO> pageInventory(Long pageNum, Long pageSize, String keyword) {
        Page<Inventory> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        // 关键词先映射到奶品 ID
        List<Long> matchedProductIds = null;
        if (StringUtils.hasText(keyword)) {
            matchedProductIds = productMapper.selectList(
                            new LambdaQueryWrapper<Product>().like(Product::getProductName, keyword))
                    .stream().map(Product::getId).collect(Collectors.toList());
            if (matchedProductIds.isEmpty()) {
                Page<InventoryVO> empty = new Page<>(page.getCurrent(), page.getSize(), 0);
                empty.setRecords(Collections.emptyList());
                return empty;
            }
        }

        LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
        if (matchedProductIds != null) {
            wrapper.in(Inventory::getProductId, matchedProductIds);
        }
        wrapper.orderByAsc(Inventory::getId);
        IPage<Inventory> invPage = page(page, wrapper);

        List<InventoryVO> voList = convertInventory(invPage.getRecords());
        Page<InventoryVO> result = new Page<>(invPage.getCurrent(), invPage.getSize(), invPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public List<InventoryVO> listWarning() {
        List<Inventory> all = lambdaQuery().orderByAsc(Inventory::getId).list();
        return convertInventory(all).stream()
                .filter(vo -> Boolean.TRUE.equals(vo.getWarning()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStock(InventoryChangeRequest request) {
        InventoryChangeType type = InventoryChangeType.of(request.getChangeType());
        if (type == null) {
            throw new BusinessException("变动类型非法");
        }
        Long operatorId = currentOperatorId();
        applyChange(request.getProductId(), type, request.getChangeQuantity(), null, operatorId, request.getRemark());
    }

    @Override
    public IPage<InventoryRecordVO> pageRecords(Long pageNum, Long pageSize, Long productId, Integer changeType) {
        Page<InventoryRecord> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        IPage<InventoryRecord> recordPage = recordMapper.selectPage(page,
                new LambdaQueryWrapper<InventoryRecord>()
                        .eq(productId != null, InventoryRecord::getProductId, productId)
                        .eq(changeType != null, InventoryRecord::getChangeType, changeType)
                        .orderByDesc(InventoryRecord::getId));

        List<InventoryRecord> records = recordPage.getRecords();
        List<InventoryRecordVO> voList = convertRecords(records);

        Page<InventoryRecordVO> result = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public void ensureInventory(Long productId) {
        Inventory exists = lambdaQuery().eq(Inventory::getProductId, productId).one();
        if (exists == null) {
            Inventory inv = new Inventory();
            inv.setProductId(productId);
            inv.setQuantity(0);
            inv.setWarningThreshold(0);
            save(inv);
        }
    }

    @Override
    public void updateThreshold(Long id, Integer warningThreshold, String warehouseLocation) {
        Inventory inv = getById(id);
        if (inv == null) {
            throw new BusinessException("库存记录不存在");
        }
        if (warningThreshold != null) {
            if (warningThreshold < 0) {
                throw new BusinessException("预警阈值不能为负");
            }
            inv.setWarningThreshold(warningThreshold);
        }
        if (warehouseLocation != null) {
            inv.setWarehouseLocation(warehouseLocation);
        }
        updateById(inv);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductForOrder(Long productId, Integer quantity, Long orderId) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("出库数量必须大于 0");
        }
        applyChange(productId, InventoryChangeType.OUTBOUND, quantity, orderId, null, "订单出库");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreForOrder(Long productId, Integer quantity, Long orderId) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("回库数量必须大于 0");
        }
        applyChange(productId, InventoryChangeType.INBOUND, quantity, orderId, null, "退订回库");
    }

    // ==================== 内部核心：唯一的库存变动出口 ====================

    /**
     * 库存变动的唯一出口：更新余量 + 写流水，必须在同一事务内
     *
     * @param productId 奶品
     * @param type      变动类型
     * @param quantity  变动数量（正整数，方向由 type 决定）
     * @param orderId   关联订单（人工变动为 null）
     * @param operatorId 操作人（系统变动为 null）
     */
    private void applyChange(Long productId, InventoryChangeType type, int quantity,
                             Long orderId, Long operatorId, String remark) {
        if (quantity <= 0) {
            throw new BusinessException("变动数量必须大于 0");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("奶品不存在");
        }

        Inventory inv = lambdaQuery().eq(Inventory::getProductId, productId).one();
        if (inv == null) {
            inv = new Inventory();
            inv.setProductId(productId);
            inv.setQuantity(0);
            inv.setWarningThreshold(0);
            save(inv);
        }

        int before = inv.getQuantity() == null ? 0 : inv.getQuantity();
        int signed = type.isIncrease() ? quantity : -quantity;
        int after = before + signed;
        if (after < 0) {
            throw new BusinessException("奶品「" + product.getProductName() + "」库存不足，当前仅剩 " + before);
        }

        // 1. 更新余量
        inv.setQuantity(after);
        updateById(inv);

        // 2. 写流水（changeQuantity 带方向符号，便于统计直接求和）
        InventoryRecord record = new InventoryRecord();
        record.setProductId(productId);
        record.setChangeType(type.getCode());
        record.setChangeQuantity(signed);
        record.setBeforeQuantity(before);
        record.setAfterQuantity(after);
        record.setOrderId(orderId);
        record.setOperatorId(operatorId);
        record.setRemark(remark);
        recordMapper.insert(record);
    }

    private Long currentOperatorId() {
        String username = SecurityUtils.getCurrentUsername();
        if (!StringUtils.hasText(username)) {
            return null;
        }
        SysUser user = sysUserService.getByUsername(username);
        return user == null ? null : user.getId();
    }

    private List<InventoryVO> convertInventory(List<Inventory> invList) {
        if (CollectionUtils.isEmpty(invList)) {
            return Collections.emptyList();
        }
        Set<Long> productIds = invList.stream().map(Inventory::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<Long> categoryIds = productMap.values().stream()
                .map(Product::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ProductCategory> categoryMap = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(ProductCategory::getId, Function.identity()));

        return invList.stream().map(inv -> {
            Product p = productMap.get(inv.getProductId());
            ProductCategory c = p == null || p.getCategoryId() == null ? null : categoryMap.get(p.getCategoryId());
            return InventoryVO.from(inv,
                    p == null ? "未知奶品" : p.getProductName(),
                    p == null ? null : p.getSpec(),
                    c == null ? null : c.getCategoryName());
        }).collect(Collectors.toList());
    }

    private List<InventoryRecordVO> convertRecords(List<InventoryRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return Collections.emptyList();
        }
        Set<Long> productIds = records.stream().map(InventoryRecord::getProductId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<Long> operatorIds = records.stream().map(InventoryRecord::getOperatorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = operatorIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserService.listByIds(operatorIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        return records.stream().map(r -> {
            Product p = productMap.get(r.getProductId());
            SysUser u = r.getOperatorId() == null ? null : userMap.get(r.getOperatorId());
            return InventoryRecordVO.from(r,
                    p == null ? "未知奶品" : p.getProductName(),
                    u == null ? (r.getOrderId() == null ? "系统" : "订单系统") : u.getRealName());
        }).collect(Collectors.toList());
    }
}
