package com.milk.order.module.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.product.dto.QuotaDeductItem;
import com.milk.order.module.product.dto.DailyQuotaBatchRequest;
import com.milk.order.module.product.entity.DailyQuota;
import com.milk.order.module.product.vo.QuotaVO;

import java.time.LocalDate;
import java.util.List;

public interface DailyQuotaService extends IService<DailyQuota> {

    /** 按日期区间查询配额（升序，回填奶品名） */
    List<QuotaVO> listRange(LocalDate startDate, LocalDate endDate);

    /** 批量设置某日各品种配额（存在则修改；已售数不得超过新总额） */
    void setQuotaBatch(LocalDate quotaDate, List<DailyQuotaBatchRequest.Item> items, String remark);

    /** 某品种某日剩余机动盒数（含保质期内结转；未设置视为 0） */
    int remaining(Long productId, LocalDate quotaDate);

    /** 某日全部品种剩余机动盒数合计（看板用） */
    int totalRemaining(LocalDate quotaDate);

    /** 某日全部在售品种的剩余盒数（供小程序逐品种展示） */
    List<QuotaVO> remainingList(LocalDate quotaDate);

    /** 按品种扣减零散订购配额（各品种先卖最老池子，写台账幂等；任一品种不足则整体抛异常） */
    void deduct(Long orderId, LocalDate deliveryDate, List<QuotaDeductItem> items);

    /** 按台账回补某订单占用的配额（退订时使用） */
    void restore(Long orderId);

    /** 按订单+品种+日期回补配额（缺货取消单期任务时使用：只回补该日期该品种的份额，不影响订单其他期次） */
    void restoreForOrderProductDate(Long orderId, Long productId, LocalDate quotaDate);

    /**
     * 拒收补送配额追加（**仅零散订单调用**，套餐订单不占配额不得调用）。
     *
     * <p>在补送日池子上追加 {@code used_quota += boxes} 并写 {@code daily_quota_usage} 台账
     * （台账 {@code quota_date} 为补送日，与支付时扣减的 {@code delivery_start_date} 不同，
     * 不撞 {@code uk_order_product_pool}）。</p>
     *
     * <p><b>不做 {@code used + boxes <= total} 上限校验</b>：破损/变质是商家责任，补送成本由商家承担，
     * 池子已满也允许追加，避免"池子满 → 补送失败 → 学生少一盒"。因此 {@code used_quota} 可能超过
     * {@code total_quota}（显式口径）；{@code QuotaLedgerInvariant} 只校验 {@code used == 台账合计}，不受影响。</p>
     *
     * <p>池子不存在时抛业务异常（无法确定补送到哪个池子）。幂等由调用方（拒收补偿台账唯一键）保证。</p>
     */
    void addCompensationBox(Long orderId, Long productId, LocalDate quotaDate, int boxes);

    /**
     * 不变量修复：以台账合计为准重算某池子的已售盒数（INV_QUOTA_LEDGER）。
     *
     * <p>只在不变量体检查出「used_quota ≠ 台账合计」时由修复器调用。
     * 以台账为重算方向是因为台账是每一笔占用的事实记录，used_quota 只是它的汇总；
     * 条件更新保证与并发扣减/回补竞争时只有一方生效，返回 false 表示已无需修复。</p>
     *
     * @param quotaId     配额池主键
     * @param ledgerBoxes 台账合计（期望的已售盒数）
     * @return true 表示本次重算生效
     */
    boolean reconcileUsedQuota(Long quotaId, int ledgerBoxes);
}
