package com.milk.order.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.product.entity.DailyQuota;
import com.milk.order.module.product.entity.DailyQuotaUsage;
import com.milk.order.module.product.mapper.DailyQuotaMapper;
import com.milk.order.module.product.mapper.DailyQuotaUsageMapper;
import com.milk.order.module.product.service.DailyQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日机动配额服务
 *
 * 结转模型：每天管理员设置的盒数是当日"新鲜池"；当日未售完的自动结转次日继续卖（牛奶不扔），
 * 但按保质期（SHELF_DAYS 天）只保留窗口内各日池子的剩余，超龄剩余自动作废（无需定时任务，
 * 计算可用量时窗口外自然不计）。扣减遵循"先卖最老的池子"（最接近过期的先出），并写订单台账供退订精确回补。
 */
@Service
@RequiredArgsConstructor
public class DailyQuotaServiceImpl extends ServiceImpl<DailyQuotaMapper, DailyQuota> implements DailyQuotaService {

    /** 牛奶保质期（天）：未售配额结转保留的天数窗口，窗口外自动作废 */
    private static final int SHELF_DAYS = 3;

    private final DailyQuotaUsageMapper dailyQuotaUsageMapper;

    @Override
    public List<DailyQuota> listRange(LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<DailyQuota> wrapper = new LambdaQueryWrapper<>();
        if (startDate != null) {
            wrapper.ge(DailyQuota::getQuotaDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(DailyQuota::getQuotaDate, endDate);
        }
        wrapper.orderByAsc(DailyQuota::getQuotaDate);
        return list(wrapper);
    }

    @Override
    public void setQuota(LocalDate quotaDate, Integer totalQuota, String remark) {
        if (quotaDate == null) {
            throw new BusinessException("请选择配额日期");
        }
        if (totalQuota == null || totalQuota < 0) {
            throw new BusinessException("机动盒数不合法");
        }
        DailyQuota existing = getByDate(quotaDate);
        if (existing == null) {
            DailyQuota quota = new DailyQuota();
            quota.setQuotaDate(quotaDate);
            quota.setTotalQuota(totalQuota);
            quota.setUsedQuota(0);
            quota.setRemark(remark);
            save(quota);
            return;
        }
        if (existing.getUsedQuota() != null && existing.getUsedQuota() > totalQuota) {
            throw new BusinessException("当日已售 " + existing.getUsedQuota() + " 盒，新配额不能小于已售数");
        }
        existing.setTotalQuota(totalQuota);
        if (StringUtils.hasText(remark)) {
            existing.setRemark(remark);
        }
        updateById(existing);
    }

    @Override
    public int remaining(LocalDate quotaDate) {
        int total = 0;
        for (int k = 0; k < SHELF_DAYS; k++) {
            DailyQuota quota = getByDate(quotaDate.minusDays(k));
            if (quota != null && quota.getTotalQuota() != null) {
                int used = quota.getUsedQuota() == null ? 0 : quota.getUsedQuota();
                total += Math.max(0, quota.getTotalQuota() - used);
            }
        }
        return total;
    }

    @Override
    public void deduct(Long orderId, LocalDate deliveryDate, int boxes) {
        if (boxes <= 0) {
            return;
        }
        if (orderId != null && !dailyQuotaUsageMapper.selectList(
                new LambdaQueryWrapper<DailyQuotaUsage>().eq(DailyQuotaUsage::getOrderId, orderId)).isEmpty()) {
            return; // 幂等：该订单已扣减过
        }
        int available = remaining(deliveryDate);
        if (available < boxes) {
            throw new BusinessException(deliveryDate + " 当日机动余量不足（剩余 " + available
                    + " 盒，含前 " + (SHELF_DAYS - 1) + " 天结转），卖完即止");
        }
        // 先卖最老的池子（最接近保质期的先出）
        int remain = boxes;
        for (int k = SHELF_DAYS - 1; k >= 0 && remain > 0; k--) {
            LocalDate poolDate = deliveryDate.minusDays(k);
            DailyQuota quota = getByDate(poolDate);
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
                usage.setQuotaDate(poolDate);
                usage.setBoxes(take);
                dailyQuotaUsageMapper.insert(usage);
            }
            remain -= take;
        }
    }

    @Override
    public void restore(Long orderId) {
        if (orderId == null) {
            return;
        }
        List<DailyQuotaUsage> usages = dailyQuotaUsageMapper.selectList(
                new LambdaQueryWrapper<DailyQuotaUsage>().eq(DailyQuotaUsage::getOrderId, orderId));
        for (DailyQuotaUsage usage : usages) {
            // 回补到原池子，避免池子被回补超卖
            LambdaUpdateWrapper<DailyQuota> update = new LambdaUpdateWrapper<>();
            update.eq(DailyQuota::getQuotaDate, usage.getQuotaDate())
                    .apply("used_quota >= {0}", usage.getBoxes())
                    .setSql("used_quota = used_quota - " + usage.getBoxes());
            update(update);
            dailyQuotaUsageMapper.deleteById(usage.getId());
        }
    }

    private DailyQuota getByDate(LocalDate quotaDate) {
        return getOne(new LambdaQueryWrapper<DailyQuota>().eq(DailyQuota::getQuotaDate, quotaDate));
    }
}
