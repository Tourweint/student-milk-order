package com.milk.order.module.warehouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.QuotaConstants;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.common.constant.WarehouseBizType;
import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.delivery.mapper.DeliveryTaskMapper;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.DailyQuotaMapper;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.warehouse.dto.WarehouseAdjustRequest;
import com.milk.order.module.warehouse.dto.WarehouseReceiptRequest;
import com.milk.order.module.warehouse.entity.WarehouseLedger;
import com.milk.order.module.warehouse.mapper.WarehouseLedgerMapper;
import com.milk.order.module.warehouse.service.WarehouseService;
import com.milk.order.module.warehouse.vo.WarehouseBalanceVO;
import com.milk.order.module.warehouse.vo.WarehouseReceiptVO;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库余量台账服务实现。
 *
 * <p>写入路径只有四条，全部**先由数据库唯一键仲裁、再由应用层翻译冲突**（禁止"先查再插"）：</p>
 * <ul>
 *   <li>{@code IN}（到货登记，人工）：{@code uk_in_receipt(biz_type, biz_date, product_id, receipt_no)}</li>
 *   <li>{@code OUT}（送出，自动）：{@code uk_biz_ref(biz_type, ref_id)}，一个任务只出一次库</li>
 *   <li>{@code IN_BACK}（拒收退回，自动）：同一条签收记录只退回一次</li>
 *   <li>{@code ADJ}（修正，人工）：人工纠错，带符号，无唯一键（同一主体可能被冲销多次）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl extends ServiceImpl<WarehouseLedgerMapper, WarehouseLedger>
        implements WarehouseService {

    /** 出入库台账的关联对象类型（与业务表名一致，便于人读） */
    private static final String REF_TYPE_TASK = "delivery_task";
    private static final String REF_TYPE_RECORD = "delivery_record";

    /** 到货登记默认凭证号：同日第二车必须显式给不同凭证号，否则视为重复登记 */
    private static final String RECEIPT_NO_DEFAULT = "MAIN";

    /** 无人上下文的自动入账（定时兜底、不变量修复）统一记 system */
    private static final String OPERATOR_SYSTEM = "system";

    private final IdempotencyGuard idempotencyGuard;
    private final ProductMapper productMapper;
    private final DailyQuotaMapper dailyQuotaMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;

    // ==================== 到货登记（R1 / R6 / R8 / R9） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseReceiptVO receipt(WarehouseReceiptRequest request) {
        LocalDate bizDate = request.getBizDate();
        if (bizDate.isAfter(LocalDate.now())) {
            throw new BusinessException("到货日期不能晚于今天：到货登记记录的是既成事实（收货点数），不做预报");
        }
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new BusinessException("奶品不存在");
        }
        String receiptNo = StringUtils.hasText(request.getReceiptNo())
                ? request.getReceiptNo().trim() : RECEIPT_NO_DEFAULT;
        // 应到（当日实物需求）与本次实到无关，先算好一并返回；口径与 R5′ 完全一致
        int expected = requiredStock(request.getProductId(), bizDate);

        WarehouseLedger ledger = new WarehouseLedger();
        ledger.setBizType(WarehouseBizType.IN);
        ledger.setBizDate(bizDate);
        ledger.setProductId(request.getProductId());
        ledger.setQuantity(request.getQuantity());
        ledger.setReceiptNo(receiptNo);
        ledger.setBatchNo(StringUtils.hasText(request.getBatchNo()) ? request.getBatchNo().trim() : null);
        ledger.setReason(request.getNote());
        ledger.setOperator(currentOperator());
        boolean inserted = idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(ledger));
        if (!inserted) {
            throw new BusinessException("「" + product.getProductName() + "」" + bizDate
                    + " 已登记过到货（凭证号 " + receiptNo + "）：同日第二车请填写各自的送货单号作为凭证号");
        }

        WarehouseReceiptVO vo = new WarehouseReceiptVO();
        vo.setId(ledger.getId());
        vo.setBizDate(bizDate);
        vo.setProductId(request.getProductId());
        vo.setProductName(product.getProductName());
        vo.setQuantity(request.getQuantity());
        vo.setReceiptNo(receiptNo);
        vo.setBatchNo(ledger.getBatchNo());
        vo.setBalance(balanceOf(request.getProductId()));
        vo.setExpectedQuantity(expected);
        if (expected > request.getQuantity()) {
            int shortfall = expected - request.getQuantity();
            vo.setShortfall(shortfall);
            vo.setWarning("「" + product.getProductName() + "」" + bizDate + " 应到 " + expected
                    + " 盒、实到 " + request.getQuantity() + " 盒，缺口 " + shortfall
                    + " 盒——请据此下调配额或安排补货（预警不阻断登记；企业分批到货时以当日累计实到为准）");
        }
        return vo;
    }

    // ==================== 查询 ====================

    @Override
    public List<WarehouseBalanceVO> balance() {
        Map<Long, Integer> balances = new HashMap<>();
        for (WarehouseBalanceVO row : baseMapper.sumBalanceGroupByProduct()) {
            balances.put(row.getProductId(), row.getBalance());
        }
        List<WarehouseBalanceVO> result = new ArrayList<>();
        Set<Long> listed = new HashSet<>();
        // 在售品种全部列出（未登记到货的余量为 0）：管理员要能一眼看到"哪个品种还没登记到货"
        for (Product p : productMapper.selectList(new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1))) {
            result.add(buildBalance(p.getId(), p.getProductName(), balances.getOrDefault(p.getId(), 0)));
            listed.add(p.getId());
        }
        // 已下架但仍有台账流水的品种也要看得到，否则历史账目"查不到品种"
        for (Map.Entry<Long, Integer> entry : balances.entrySet()) {
            if (listed.contains(entry.getKey())) {
                continue;
            }
            Product p = productMapper.selectById(entry.getKey());
            result.add(buildBalance(entry.getKey(), p == null ? null : p.getProductName(), entry.getValue()));
        }
        result.sort(Comparator.comparing(WarehouseBalanceVO::getProductId));
        return result;
    }

    @Override
    public IPage<WarehouseLedger> pageLedger(Long pageNum, Long pageSize, String bizType, Long productId,
                                             String startDate, String endDate) {
        Page<WarehouseLedger> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE
                        : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));
        LambdaQueryWrapper<WarehouseLedger> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(bizType), WarehouseLedger::getBizType, bizType)
                .eq(productId != null, WarehouseLedger::getProductId, productId)
                .ge(StringUtils.hasText(startDate), WarehouseLedger::getBizDate,
                        StringUtils.hasText(startDate) ? LocalDate.parse(startDate) : null)
                .le(StringUtils.hasText(endDate), WarehouseLedger::getBizDate,
                        StringUtils.hasText(endDate) ? LocalDate.parse(endDate) : null)
                .orderByDesc(WarehouseLedger::getId);
        return page(page, wrapper);
    }

    // ==================== 修正（R7：只增不改，反向冲销） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjust(WarehouseAdjustRequest request) {
        int signed = request.getQuantity();
        if (signed == 0) {
            throw new BusinessException("修正盒数不能为 0（正数=增加余量，负数=减少余量）");
        }
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new BusinessException("奶品不存在");
        }
        // 与发行校验同一把锁：否则两个并发减记会各自读到旧余量后双双通过，把 W 冲成负数
        productMapper.selectForUpdate(request.getProductId());
        if (signed < 0) {
            int balance = balanceOf(request.getProductId());
            if (balance + signed < 0) {
                throw new BusinessException("修正后「" + product.getProductName() + "」的仓库余量将为 "
                        + (balance + signed) + " 盒（当前 " + balance + " 盒）：余量不允许为负，"
                        + "请先核对台账（到货/送出/退回）再决定冲销数量");
            }
        }
        WarehouseLedger ledger = new WarehouseLedger();
        ledger.setBizType(WarehouseBizType.ADJ);
        ledger.setBizDate(LocalDate.now());
        ledger.setProductId(request.getProductId());
        ledger.setQuantity(signed);
        ledger.setReason(request.getReason());
        ledger.setOperator(currentOperator());
        baseMapper.insert(ledger);
    }

    // ==================== 余量 / 需求 / 发行封顶 ====================

    @Override
    public int balanceOf(Long productId) {
        if (productId == null) {
            return 0;
        }
        return nz(baseMapper.sumBalance(productId));
    }

    @Override
    public int requiredStock(Long productId, LocalDate baseDate) {
        LocalDate from = baseDate.minusDays(QuotaConstants.SHELF_DAYS - 1L);
        int quotaRemaining = nz(dailyQuotaMapper.sumRemainingInWindow(productId, from, baseDate));
        int pendingTasks = nz(deliveryTaskMapper.sumPendingQuantityUpTo(productId, baseDate));
        return quotaRemaining + pendingTasks;
    }

    @Override
    public void requireIssuanceCoverage(Long productId, LocalDate poolDate, int newTotalQuota, int poolUsedQuota) {
        // 同品种发行串行化：W 的聚合读本身无锁，同品种并发发行（不同日期）会各自读到旧 W 后双双通过。
        // 加锁顺序固定 product → daily_quota（扣减路径只锁 daily_quota 行），不会成环。
        productMapper.selectForUpdate(productId);

        LocalDate from = poolDate.minusDays(QuotaConstants.SHELF_DAYS - 1L);
        // 结转池（D−2、D−1）的未售额度：它们仍可被 D 日的订单扣走，故与 D 日的配额一起占用实物
        int carryOver = nz(dailyQuotaMapper.sumRemainingInWindow(productId, from, poolDate.minusDays(1)));
        // 本次要设置的池：用"新总额 − 已售"而不是"旧总额 − 已售"，校验的是设置之后的状态
        int selfRemaining = Math.max(0, newTotalQuota - poolUsedQuota);
        int pendingTasks = nz(deliveryTaskMapper.sumPendingQuantityUpTo(productId, poolDate));
        int demand = carryOver + selfRemaining + pendingTasks;
        int warehouse = balanceOf(productId);
        if (demand > warehouse) {
            Product p = productMapper.selectById(productId);
            String name = p == null ? "奶品" + productId : p.getProductName();
            throw new BusinessException("仓库余量不足，「" + name + "」" + poolDate + " 的机动配额设置被拒绝："
                    + "该日实物需求 " + demand + " 盒（本次配额未售部分 " + selfRemaining
                    + " + 结转池未售 " + carryOver + " + 待送出任务 " + pendingTasks
                    + "）> 仓库余量 " + warehouse + " 盒，请先登记到货或下调配额");
        }
    }

    // ==================== 过程钩子 ====================

    @Override
    public boolean recordOutStock(Long taskId, Long productId, LocalDate deliveryDate, int quantity) {
        if (taskId == null || productId == null || deliveryDate == null || quantity <= 0) {
            // 不静默放行：写出可定位的日志，让"账少了"在排查时能被看见（而不是等对账才发现）
            log.warn("[仓库台账] OUT 入账参数不完整，已跳过：taskId={}, productId={}, deliveryDate={}, quantity={}",
                    taskId, productId, deliveryDate, quantity);
            return false;
        }
        WarehouseLedger ledger = new WarehouseLedger();
        ledger.setBizType(WarehouseBizType.OUT);
        ledger.setBizDate(deliveryDate);
        ledger.setProductId(productId);
        ledger.setQuantity(quantity);
        ledger.setRefType(REF_TYPE_TASK);
        ledger.setRefId(taskId);
        ledger.setReason("任务送出自动出库");
        ledger.setOperator(currentOperator());
        // 幂等由 uk_biz_ref 仲裁：重复批量送出、多实例并发、不变量补写都只落一行
        return idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(ledger));
    }

    @Override
    public boolean recordInBack(Long recordId, Long productId, LocalDate bizDate, int quantity, String reason) {
        if (recordId == null || productId == null || bizDate == null || quantity <= 0) {
            log.warn("[仓库台账] IN_BACK 入账参数不完整，已跳过：recordId={}, productId={}, bizDate={}, quantity={}",
                    recordId, productId, bizDate, quantity);
            return false;
        }
        WarehouseLedger ledger = new WarehouseLedger();
        ledger.setBizType(WarehouseBizType.IN_BACK);
        ledger.setBizDate(bizDate);
        ledger.setProductId(productId);
        ledger.setQuantity(quantity);
        ledger.setRefType(REF_TYPE_RECORD);
        ledger.setRefId(recordId);
        ledger.setReason(StringUtils.hasText(reason) ? reason : "拒收退回");
        ledger.setOperator(currentOperator());
        return idempotencyGuard.insertIgnoringDuplicate(() -> baseMapper.insert(ledger));
    }

    // ==================== 内部工具 ====================

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String currentOperator() {
        String username = SecurityUtils.getCurrentUsername();
        return StringUtils.hasText(username) ? username : OPERATOR_SYSTEM;
    }

    private WarehouseBalanceVO buildBalance(Long productId, String productName, Integer balance) {
        WarehouseBalanceVO vo = new WarehouseBalanceVO();
        vo.setProductId(productId);
        vo.setProductName(productName);
        vo.setBalance(balance == null ? 0 : balance);
        return vo;
    }
}
