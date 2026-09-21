package com.milk.order.module.warehouse.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 仓库余量台账（供给侧：履约链起点）。
 *
 * <p><b>恒等式</b>：{@code W(品种) = ΣIN + ΣIN_BACK + ΣINIT + ΣADJ(带符号) − ΣOUT}，任何时刻 {@code W ≥ 0}。</p>
 *
 * <p><b>只增不改</b>（与 {@code process_transition_log} 同一原则）：台账是事实记录，
 * 只允许新增与查询；记错走反向 {@code ADJ} 冲销，两条留痕。业务代码不得更新或删除本表。</p>
 *
 * <p><b>不是库存系统</b>：不做效期、先进先出、盘点、库位——那些属于奶站/学校 ERP 的职责，
 * 本表只回答"还有多少盒可卖"。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("warehouse_ledger")
public class WarehouseLedger extends BaseEntity {

    /** 账目类型：IN / OUT / IN_BACK / ADJ / INIT，见 {@code WarehouseBizType} */
    private String bizType;

    /** 业务日期：IN=到货日；OUT=任务配送日期；IN_BACK=拒收日；ADJ/INIT=登记日 */
    private LocalDate bizDate;

    /** 奶品 ID（按品种独立记账，不混账） */
    private Long productId;

    /**
     * 盒数。
     *
     * <p>IN/OUT/IN_BACK/INIT 恒为正（方向由 {@code bizType} 表达）；
     * <b>ADJ 带符号</b>（正=增加余量，负=减少余量）——否则"反向 ADJ 冲销"无从实现。</p>
     */
    private Integer quantity;

    /** 关联对象类型：OUT=delivery_task / IN_BACK=delivery_record；其余为空 */
    private String refType;

    /** 关联对象 ID（OUT/IN_BACK 的幂等业务键，见唯一键 {@code uk_biz_ref}） */
    private Long refId;

    /** 到货凭证号：IN 必填（首行 MAIN、同日第二车填送货单号）；INIT 固定 INIT；其余为空 */
    private String receiptNo;

    /** 可选：到货批次号（批次追溯钩子的数据来源，不参与任何流转） */
    private String batchNo;

    /** ADJ / IN_BACK 必填（修正原因 / 拒收原因）；IN 可填供应商与送货单号 */
    private String reason;

    /** 操作人（自动入账记 system，人工登记记登录用户名） */
    private String operator;
}
