package com.milk.order.module.warehouse.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 到货登记结果（含短交预警）。
 *
 * <p>预警是**提示不是闸门**：企业可能分批到货，第二车实到会让累计追平，
 * 因此差异只作为消息回传（R9），真正的闸门是配额发行时的封顶校验（R5′）。</p>
 */
@Data
public class WarehouseReceiptVO {

    /** 台账行 ID */
    private Long id;

    /** 到货日期 */
    private LocalDate bizDate;

    /** 奶品 ID */
    private Long productId;

    /** 奶品名称 */
    private String productName;

    /** 本次实到盒数 */
    private Integer quantity;

    /** 到货凭证号（未填时为默认 MAIN） */
    private String receiptNo;

    /** 批次号（可选） */
    private String batchNo;

    /** 登记后的仓库余量 W（盒） */
    private Integer balance;

    /** 当日应到（口径见设计方案 §11.2：D 日实物需求 = 池剩余 + 待送出任务） */
    private Integer expectedQuantity;

    /** 缺口 = 应到 − 实到（> 0 表示短交；无缺口时为 null） */
    private Integer shortfall;

    /** 短交预警消息（无缺口时为 null） */
    private String warning;
}
