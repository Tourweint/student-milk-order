package com.milk.order.module.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 奶品批次（**批次追溯钩子**，不参与任何业务流转）
 *
 * <p>建档判断：本系统配送常温奶，真实质量风险是**批次事故**（灭菌故障、包材破损 → 同批数千盒不合格），
 * 处理它需要回答"这批奶送给了哪些孩子"。但批次属奶站/工厂责任域，系统建模的是**供货计划**而非奶的实体
 * （见 {@code docs/设计方案/2026-09-21-保质期语义校正与批次追溯-设计方案.md}），因此这里只做**钩子**：
 * 批号作为可选标注挂在配额池上，反查链为「批号 → 配额池 → 扣减台账 → 订单/任务/学生」。</p>
 *
 * <p><b>刻意不建的东西</b>：不建入库/出库/效期预警（那是奶站 ERP 的职责域），不参与扣减、结转、台账口径，
 * 不引入新的状态机与状态迁移。</p>
 *
 * <p><b>覆盖边界</b>：学期套餐不占配额、不经配额池，因此批号反查只覆盖**单日零散订购**；
 * 套餐用奶的批次召回需按配送日与奶站人工对照。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product_batch")
public class ProductBatch extends BaseEntity {

    /** 批号（奶站/工厂批号，召回反查的业务键，全局唯一） */
    private String batchNo;

    /** 奶品 ID */
    private Long productId;

    /** 生产日期 */
    private LocalDate productionDate;

    /** 到货日期 */
    private LocalDate arrivalDate;

    /** 状态：1-正常，2-召回中，3-已停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
