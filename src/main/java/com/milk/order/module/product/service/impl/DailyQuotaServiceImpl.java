package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.dto.DailyQuotaBatchRequest;
import com.milk.order.module.product.dto.QuotaDeductItem;
import com.milk.order.module.product.entity.DailyQuota;
import com.milk.order.module.product.entity.DailyQuotaUsage;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.DailyQuotaMapper;
import com.milk.order.module.product.mapper.DailyQuotaUsageMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.product.service.DailyQuotaService;
import com.milk.order.module.product.vo.QuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每日机动配额服务（按品种）
 *
 * 结转模型：每个品种每天管理员设置的盒数是当日"新鲜池"；当日未售完的自动结转次日继续卖（牛奶不扔），
 * 按保质期（SHELF_DAYS 天）滚动，窗口外的剩余自动作废（动态计算，无需定时任务）。
 * 扣减按品种独立进行、先卖最老的池子（最接近过期的先出），并写订单台账供退订精确回补。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyQuotaServiceImpl extends ServiceImpl<DailyQuotaMapper, DailyQuota> implements DailyQuotaService {

    /** 牛奶保质期（天）：未售配额结转保留的天数窗口，窗口外自动作废 */
    private static final int SHELF_DAYS = 3;

    private final DailyQuotaUsageMapper dailyQuotaUsageMapper;
    private final ProductMapper productMapper;

    @Override
    public List<QuotaVO> listRange(LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<DailyQuota> wrapper = new LambdaQueryWrapper<>();
        if (startDate != null) {
            wrapper.ge(DailyQuota::getQuotaDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(DailyQuota::getQuotaDate, endDate);
        }
        wrapper.orderByAsc(DailyQuota::getQuotaDate).orderByAsc(DailyQuota::getProductId);
        List<DailyQuota> rows = list(wrapper);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Product> productMap = productMapper.selectBatchIds(
                        rows.stream().map(DailyQuota::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return rows.stream().map(r -> {
            QuotaVO vo = new QuotaVO();
            vo.setId(r.getId());
            vo.setQuotaDate(r.getQuotaDate());
            vo.setProductId(r.getProductId());
            Product p = productMap.get(r.getProductId());
            vo.setProductName(p == null ? null : p.getProductName());
            vo.setTotalQuota(r.getTotalQuota());
            vo.setUsedQuota(r.getUsedQuota());
            vo.setRemaining(Math.max(0, r.getTotalQuota() - (r.getUsedQuota() == null ? 0 : r.getUsedQuota())));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setQuotaBatch(LocalDate quotaDate, List<DailyQuotaBatchRequest.Item> items, String remark) {
        if (quotaDate == null) {
            throw new BusinessException("请选择配额日期");
        }
        if (CollectionUtils.isEmpty(items)) {
            throw new BusinessException("请至少设置一个品种的配额");
        }
        for (DailyQuotaBatchRequest.Item item : items) {
            if (item.getTotalQuota() == null || item.getTotalQuota() < 0) {
                throw new BusinessException("机动盒数不合法");
            }
            DailyQuota existing = getByDateAndProduct(quotaDate, item.getProductId());
            if (existing == null) {
                DailyQuota quota = new DailyQuota();
                quota.setQuotaDate(quotaDate);
                quota.setProductId(item.getProductId());
                quota.setTotalQuota(item.getTotalQuota());
                quota.setUsedQuota(0);
                quota.setRemark(remark);
                save(quota);
            } else {
                int used = existing.getUsedQuota() == null ? 0 : existing.getUsedQuota();
                if (used > item.getTotalQuota()) {
                    Product p = productMapper.selectById(item.getProductId());
                    String name = p == null ? "奶品" + item.getProductId() : p.getProductName();
                    throw new BusinessException("「" + name + "」当日已售 " + used + " 盒，新配额不能小于已售数");
                }
                existing.setTotalQuota(item.getTotalQuota());
                if (StringUtils.hasText(remark)) {
                    existing.setRemark(remark);
                }
                updateById(existing);
            }
        }
    }

    @Override
    public int remaining(Long productId, LocalDate quotaDate) {
        int total = 0;
        for (int k = 0; k < SHELF_DAYS; k++) {
            DailyQuota quota = getByDateAndProduct(quotaDate.minusDays(k), productId);
            if (quota != null && quota.getTotalQuota() != null) {
                int used = quota.getUsedQuota() == null ? 0 : quota.getUsedQuota();
                total += Math.max(0, quota.getTotalQuota() - used);
            }
        }
        return total;
    }

    @Override
    public int totalRemaining(LocalDate quotaDate) {
        return collectRemainingByProduct(quotaDate).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public List<QuotaVO> remainingList(LocalDate quotaDate) {
        // 全部在售品种（含未设置配额的，剩余按 0 展示）
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1));
        Map<Long, Integer> remaining = collectRemainingByProduct(quotaDate);
        List<QuotaVO> result = new ArrayList<>();
        for (Product p : products) {
            QuotaVO vo = new QuotaVO();
            vo.setProductId(p.getId());
            vo.setProductName(p.getProductName());
            vo.setRemaining(remaining.getOrDefault(p.getId(), 0));
            result.add(vo);
        }
        return result;
    }

    /** 计算各品种在保质期窗口内的剩余合计 */
    private Map<Long, Integer> collectRemainingByProduct(LocalDate quotaDate) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (int k = 0; k < SHELF_DAYS; k++) {
            LocalDate date = quotaDate.minusDays(k);
            for (DailyQuota quota : list(new LambdaQueryWrapper<DailyQuota>().eq(DailyQuota::getQuotaDate, date))) {
                int used = quota.getUsedQuota() == null ? 0 : quota.getUsedQuota();
                remaining.merge(quota.getProductId(), Math.max(0, quota.getTotalQuota() - used), Integer::sum);
            }
        }
        return remaining;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long orderId, LocalDate deliveryDate, List<QuotaDeductItem> items) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        if (orderId != null && !dailyQuotaUsageMapper.selectList(
                new LambdaQueryWrapper<DailyQuotaUsage>().eq(DailyQuotaUsage::getOrderId, orderId)).isEmpty()) {
            return; // 幂等：该订单已扣减过
        }
        // 逐品种校验余量（全部充足才开始扣，避免部分扣减）
        Map<Long, Integer> need = new LinkedHashMap<>();
        for (QuotaDeductItem item : items) {
            need.merge(item.getProductId(), item.getBoxes(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : need.entrySet()) {
            int available = remaining(entry.getKey(), deliveryDate);
            if (available < entry.getValue()) {
                Product p = productMapper.selectById(entry.getKey());
                String name = p == null ? "奶品" + entry.getKey() : p.getProductName();
                throw new BusinessException("「" + name + "」" + deliveryDate + " 剩余库存 " + available
                        + " 盒，不足本次订购的 " + entry.getValue() + " 盒，卖完即止");
            }
        }
        // 先卖最老的池子（最接近保质期的先出）
        for (Map.Entry<Long, Integer> entry : need.entrySet()) {
            int remain = entry.getValue();
            for (int k = SHELF_DAYS - 1; k >= 0 && remain > 0; k--) {
                LocalDate poolDate = deliveryDate.minusDays(k);
                DailyQuota quota = getByDateAndProduct(poolDate, entry.getKey());
                if (quota == null) {
                    continue;
                }
                int poolRemaining = quota.getTotalQuota() - (quota.getUsedQuota() == null ? 0 : quota.getUsedQuota());
                if (poolRemaining <= 0) {
                    continue;
                }
                int take = Math.min(poolRemaining, remain);
                LambdaUpdateWrapper<DailyQuota> update = new LambdaUpdateWrapper<>();
                update.eq(DailyQuota::getId, quota.getId())
                        .apply("used_quota + {0} <= total_quota", take)
                        .setSql("used_quota = used_quota + " + take);
                if (!update(update)) {
                    throw new BusinessException("配额扣减冲突，请重试");
                }
                if (orderId != null) {
                    DailyQuotaUsage usage = new DailyQuotaUsage();
                    usage.setOrderId(orderId);
                    usage.setProductId(entry.getKey());
                    usage.setQuotaDate(poolDate);
                    usage.setBoxes(take);
                    dailyQuotaUsageMapper.insert(usage);
                }
                remain -= take;
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long orderId) {
        if (orderId == null) {
            return;
        }
        List<DailyQuotaUsage> usages = dailyQuotaUsageMapper.selectList(
                new LambdaQueryWrapper<DailyQuotaUsage>().eq(DailyQuotaUsage::getOrderId, orderId));
        for (DailyQuotaUsage usage : usages) {
            // 回补到原池子，避免回补超卖
            LambdaUpdateWrapper<DailyQuota> update = new LambdaUpdateWrapper<>();
            update.eq(DailyQuota::getQuotaDate, usage.getQuotaDate())
                    .eq(DailyQuota::getProductId, usage.getProductId())
                    .apply("used_quota >= {0}", usage.getBoxes())
                    .setSql("used_quota = used_quota - " + usage.getBoxes());
            update(update);
            dailyQuotaUsageMapper.deleteById(usage.getId());
        }
    }

    /**
     * 按订单+品种回补配额（缺货取消单期任务专用）：
     * 取台账中「该订单 × 该品种」的全部扣减行，逐行回补到各自原始池子（含保质期结转池），
     * 不影响同订单其他品种的占用。零散订单为单日订单，其该品种台账即缺货当日份额。
     * 幂等：对应台账行回补成功即删除，重复调用自然跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreForOrderProductDate(Long orderId, Long productId, LocalDate quotaDate) {
        if (orderId == null || productId == null) {
            return;
        }
        List<DailyQuotaUsage> usages = dailyQuotaUsageMapper.selectList(
                new LambdaQueryWrapper<DailyQuotaUsage>()
                        .eq(DailyQuotaUsage::getOrderId, orderId)
                        .eq(DailyQuotaUsage::getProductId, productId));
        if (usages.isEmpty()) {
            return; // 该订单该品种无扣减台账（如学期套餐不占配额）
        }
        int restored = 0;
        for (DailyQuotaUsage usage : usages) {
            // 回补到台账记录的原始池子，且不允许把 used_quota 减到负数（防御性条件更新）
            LambdaUpdateWrapper<DailyQuota> update = new LambdaUpdateWrapper<>();
            update.eq(DailyQuota::getQuotaDate, usage.getQuotaDate())
                    .eq(DailyQuota::getProductId, usage.getProductId())
                    .apply("used_quota >= {0}", usage.getBoxes())
                    .setSql("used_quota = used_quota - " + usage.getBoxes());
            if (update(update)) {
                dailyQuotaUsageMapper.deleteById(usage.getId());
                restored += usage.getBoxes() == null ? 0 : usage.getBoxes();
            } else {
                log.warn("[配额回补] 订单 {} 品种 {} 池子 {} 回补冲突（used_quota 小于应回补数），跳过该行",
                        orderId, productId, usage.getQuotaDate());
            }
        }
        if (restored > 0) {
            log.info("[配额回补] 订单 {} 品种 {} 共回补 {} 盒（缺货单期取消）", orderId, productId, restored);
        }
    }

    private DailyQuota getByDateAndProduct(LocalDate quotaDate, Long productId) {
        return getOne(new LambdaQueryWrapper<DailyQuota>()
                .eq(DailyQuota::getQuotaDate, quotaDate)
                .eq(DailyQuota::getProductId, productId));
    }
}
