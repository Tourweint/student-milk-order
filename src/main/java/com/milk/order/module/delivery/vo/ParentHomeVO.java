package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 家长端首页聚合视图：剩余待配送盒数 + 下次配送日 + 近期拒收。
 *
 * <p>只读、数据范围限定为当前家长绑定的学生（Service 层经 DataScopeResolver 解析）。
 * 「近期拒收」只取**真拒收**（已写原因分类），可用于向家长解释"剩余为什么少了一盒"。</p>
 */
@Data
public class ParentHomeVO implements Serializable {

    /** 剩余待配送盒数（未完成 = 待配送 + 配送中） */
    private int pendingQuantity;

    /** 下次配送日（该学生最早一条待配送任务的配送日期；无则 null） */
    private LocalDate nextDeliveryDate;

    /** 近期拒收（按记录倒序，最多 N 条） */
    private List<RecentReject> recentRejects = new ArrayList<>();

    /** 一条近期拒收 */
    @Data
    public static class RecentReject implements Serializable {

        /** 配送记录 ID */
        private Long recordId;

        /** 配送日期 */
        private LocalDate deliveryDate;

        /** 奶品名称 */
        private String productName;

        /** 拒收原因分类编码 */
        private String reasonCode;

        /** 拒收原因中文（含详细描述），由后端映射，前端直接展示 */
        private String reasonText;
    }
}
