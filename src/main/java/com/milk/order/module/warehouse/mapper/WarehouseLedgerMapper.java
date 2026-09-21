package com.milk.order.module.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.warehouse.entity.WarehouseLedger;
import com.milk.order.module.warehouse.vo.WarehouseBalanceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 仓库台账 Mapper（只增查：不提供更新与删除的原生语句）。
 *
 * <p><b>余量恒等式</b>：{@code W = Σ(IN, IN_BACK, INIT) + Σ(ADJ 带符号) − Σ(OUT)}。
 * 这条 CASE 表达式在三处出现（单品种余量、全品种余量、不变量探测），刻意保持字面一致——
 * 一旦某处漏掉 {@code ELSE quantity} 分支，ADJ 冲销就会被当成"减少"而算错账。</p>
 *
 * <p>原生 SQL 不走 MyBatis-Plus 的 {@code @TableLogic} 自动拼接，故 {@code deleted = 0} 必须显式写。</p>
 */
@Mapper
public interface WarehouseLedgerMapper extends BaseMapper<WarehouseLedger> {

    /** 单品种当前余量 W（不存在台账行时为 0） */
    @Select("SELECT IFNULL(SUM(CASE WHEN biz_type IN ('IN', 'IN_BACK', 'INIT') THEN quantity "
            + "WHEN biz_type = 'OUT' THEN -quantity "
            + "ELSE quantity END), 0) "
            + "FROM warehouse_ledger WHERE product_id = #{productId} AND deleted = 0")
    Integer sumBalance(@Param("productId") Long productId);

    /**
     * 全品种余量（仅含出现过台账流水的品种）。
     *
     * <p>列别名即 {@link WarehouseBalanceVO} 的属性名，直接作为投影载体，避免为一次聚合再造一个类。</p>
     */
    @Select("SELECT product_id AS productId, "
            + "SUM(CASE WHEN biz_type IN ('IN', 'IN_BACK', 'INIT') THEN quantity "
            + "WHEN biz_type = 'OUT' THEN -quantity "
            + "ELSE quantity END) AS balance "
            + "FROM warehouse_ledger WHERE deleted = 0 GROUP BY product_id")
    List<WarehouseBalanceVO> sumBalanceGroupByProduct();
}
