package com.milk.order.module.product.vo;

import com.milk.order.module.product.entity.ProductBatch;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 批次召回反查结果：批号 → 关联配额池（日期×品种）→ 扣减台账 → 订单/任务/学生
 *
 * <p>用途：批次事故（同批数千盒不合格）与监管检查时回答"这批奶给了谁"。</p>
 *
 * <p><b>覆盖边界</b>：反查入口是配额池上的 {@code batch_no} 标注，而学期套餐不经配额池，
 * 因此结果只覆盖**单日零散订购**；套餐用奶需按配送日与奶站人工对照。</p>
 */
@Data
public class BatchTraceVO implements Serializable {

    /** 已登记的批次档案（未登记时为空——反查以配额池标注为准，不强制先建档，便于事故当场直接查） */
    private ProductBatch batch;

    /** 该批号标注过的配额池（即"这批奶铺在哪些日期/品种的计划池上"） */
    private List<PoolRow> pools;

    /** 反查命中明细（召回工作清单：谁、哪天、哪条任务、几盒、签收状态） */
    private List<DeliveryRow> deliveries;

    /** 命中台账合计盒数（召回规模概览） */
    private Integer totalBoxes;

    /** 关联池子数量 */
    private Integer poolCount;

    /** 批号 → 配额池：某个被标注的池子 */
    @Data
    public static class PoolRow implements Serializable {
        private LocalDate quotaDate;
        private Long productId;
        private String productName;
        private Integer totalQuota;
        private Integer usedQuota;
        private String remark;
    }

    /** 批号 → 台账 → 订单/任务/学生：一行 = 一次涉及该批次的配送（按订单×品种×池子日期） */
    @Data
    public static class DeliveryRow implements Serializable {
        /** 扣减台账所在池子日期（= 奶出库批次所铺的日期） */
        private LocalDate quotaDate;
        private Long orderId;
        private String orderNo;
        private Long studentId;
        private String studentName;
        private String className;
        private Long productId;
        private String productName;
        /** 扣减盒数 */
        private Integer boxes;
        /** 该订单该品种的配送日（零散订购起止同日，此处即台账池子被扣当日） */
        private LocalDate deliveryDate;
        /** 配送任务号（任务可能因平移/重排而作废，故可能查不到） */
        private String taskNo;
        /** 配送任务状态：1-待配送，2-配送中，3-已完成，4-已取消 */
        private Integer taskStatus;
        /** 签收状态：1-已签收，2-未签收，3-已拒收；任务不存在时为 null */
        private Integer signStatus;
    }
}
