package com.milk.order.module.warehouse.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 到货登记请求（配送站收货点数时提交，每日每品种一行）。
 */
@Data
public class WarehouseReceiptRequest {

    /**
     * 到货日期。
     *
     * <p>不得晚于今天：到货登记记录的是**既成事实**（收货点数），不做预报——
     * 允许登记未来日期等于允许"凭空增加余量"，会让发行封顶失去物理依据。</p>
     */
    @NotNull(message = "请选择到货日期")
    private LocalDate bizDate;

    /** 奶品 ID */
    @NotNull(message = "奶品不能为空")
    private Long productId;

    /** 实到盒数（>0）：比计划多出的部分自动成为余量并提高次日配额上限 */
    @NotNull(message = "请填写到货盒数")
    @Min(value = 1, message = "到货盒数必须大于 0")
    private Integer quantity;

    /** 可选：到货批次号（批次追溯钩子，事故当场可直接标注，不要求先建档） */
    private String batchNo;

    /**
     * 到货凭证号（可选）。
     *
     * <p>不填按 {@code MAIN} 落账；**同日同品种第二车必须填各自送货单号**——
     * 唯一键 {@code uk_in_receipt(type, date, product, receipt_no)} 因此既挡住双击重复提交，
     * 又允许分批到货如实登记（而不是让配送站走"管理员才有的修正"）。</p>
     */
    private String receiptNo;

    /** 可选：备注（供应商 / 送货单号等），写入台账 {@code reason} */
    private String note;
}
