package com.milk.order.experiment;

import com.milk.order.module.product.dto.DailyQuotaBatchRequest;
import com.milk.order.module.product.entity.ProductBatch;
import com.milk.order.module.product.service.ProductBatchService;
import com.milk.order.module.product.vo.BatchTraceVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验十六：批次追溯钩子（召回反查）。
 *
 * <p>命题（对应《后续扩展方案》§4.7）：批次只是**配额池上的可选标注**——
 * 不参与扣减、结转与任何状态流转，但必须能回答"这批奶送给了哪些孩子"。因此本实验只验三件事：
 * ① 反查链路完整（批号 → 池子 → 台账 → 订单/学生/班级 → 任务/签收状态）；
 * ② 边界成立（未建档也可反查；未标注返回空而不是报错）；
 * ③ 与结转语义不冲突（池子日期可能早于配送日，结果里两者分列，不合并成一个"日期"）。</p>
 *
 * <p>实验全程只写独立实验库；批次不触达状态机，故无需驱动自愈通道。</p>
 */
@DisplayName("实验十六：批次追溯钩子（召回反查）")
class Experiment16BatchTraceTest extends ExperimentSupport {

    @Autowired
    private ProductBatchService productBatchService;

    @Test
    @DisplayName("反查链路：批号 → 池子 → 台账 → 订单/学生/班级 → 配送任务与签收状态")
    void traceCoversPoolLedgerStudentAndTask() {
        LocalDate date = LocalDate.now().plusDays(1);
        String batchNo = TAG + "-B1";
        createBatch(batchNo, date);
        tagQuotaWithBatch(date, 10, batchNo);

        long orderId = newPendingOrder(date, date, 2);
        orderInfoService.payOrder(orderId);
        long taskId = taskIdOf(orderId, date);
        deliveryTaskService.startDelivery(taskId);
        signRecord(recordIdOf(taskId));

        BatchTraceVO trace = productBatchService.trace(batchNo);
        assertThat(trace.getDeliveries()).hasSize(1);
        BatchTraceVO.DeliveryRow row = trace.getDeliveries().get(0);

        report("实验十六 · 批号反查链路",
                "批次档案（期望 已建档）", trace.getBatch() == null ? "无" : trace.getBatch().getBatchNo(),
                "关联池子数（期望 1）", trace.getPoolCount(),
                "命中台账盒数（期望 2）", trace.getTotalBoxes(),
                "清单行数（期望 1）", trace.getDeliveries().size(),
                "订单 / 学生 / 班级", row.getOrderNo() + " / " + row.getStudentName() + " / " + row.getClassName(),
                "池子日期 / 配送日（期望同为 " + date + "）", row.getQuotaDate() + " / " + row.getDeliveryDate(),
                "任务状态（期望 3 已完成）/ 签收状态（期望 1 已签收）",
                row.getTaskStatus() + " / " + row.getSignStatus());

        assertThat(trace.getBatch()).isNotNull();
        assertThat(trace.getPoolCount()).isEqualTo(1);
        assertThat(trace.getTotalBoxes()).isEqualTo(2);
        assertThat(row.getOrderId()).isEqualTo(orderId);
        assertThat(row.getStudentId()).isEqualTo(studentId);
        assertThat(row.getStudentName()).isEqualTo(TAG + "学生");
        assertThat(row.getClassName()).isEqualTo(TAG + "班");
        assertThat(row.getProductId()).isEqualTo(productId);
        assertThat(row.getBoxes()).isEqualTo(2);
        assertThat(row.getDeliveryDate()).isEqualTo(date);
        assertThat(row.getQuotaDate()).isEqualTo(date);
        assertThat(row.getTaskNo()).isNotNull();
        assertThat(row.getTaskStatus()).isEqualTo(3);
        assertThat(row.getSignStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("边界：未建档的批号也能反查；未标注的批号返回空而不是报错")
    void traceWorksWithoutArchiveAndReportsEmpty() {
        LocalDate date = LocalDate.now().plusDays(1);
        // 刻意不建 product_batch 档案：事故当场应能直接用批号查
        String unregistered = TAG + "-B2";
        tagQuotaWithBatch(date, 5, unregistered);
        long orderId = newPendingOrder(date, date, 1);
        orderInfoService.payOrder(orderId);

        BatchTraceVO trace = productBatchService.trace(unregistered);
        BatchTraceVO empty = productBatchService.trace(TAG + "-NOT-EXIST");

        report("实验十六 · 未建档与未标注边界",
                "未建档批号是否可反查（期望 档案为空、池子 1）",
                (trace.getBatch() == null ? "档案为空" : "有档案") + "、池子 " + trace.getPoolCount(),
                "未建档批号反查明细行数（期望 1）", trace.getDeliveries().size(),
                "未标注批号池子数（期望 0）", empty.getPoolCount(),
                "未标注批号明细行数（期望 0）", empty.getDeliveries().size());

        assertThat(trace.getBatch()).isNull();
        assertThat(trace.getPoolCount()).isEqualTo(1);
        assertThat(trace.getDeliveries()).hasSize(1);
        assertThat(trace.getDeliveries().get(0).getTaskNo()).isNotNull();
        assertThat(empty.getBatch()).isNull();
        assertThat(empty.getPoolCount()).isZero();
        assertThat(empty.getDeliveries()).isEmpty();
        assertThat(empty.getTotalBoxes()).isZero();
    }

    @Test
    @DisplayName("与结转共存：扣减走的是前一天的池子，池子日期与配送日分列显示")
    void traceKeepsPoolDateApartFromDeliveryDate() {
        LocalDate deliveryDate = LocalDate.now().plusDays(2);
        LocalDate poolDate = deliveryDate.minusDays(1);
        String batchNo = TAG + "-B3";
        createBatch(batchNo, poolDate);
        // 只给前一天建池并标注批次（配送当日不建池）——扣减会先消耗最老的池子，于是台账落在前一天
        tagQuotaWithBatch(poolDate, 3, batchNo);

        long orderId = newPendingOrder(deliveryDate, deliveryDate, 1);
        orderInfoService.payOrder(orderId);

        BatchTraceVO trace = productBatchService.trace(batchNo);
        BatchTraceVO.DeliveryRow row = trace.getDeliveries().get(0);

        report("实验十六 · 结转与批次共存",
                "配送日 / 扣减池子日（期望相差 1 天）", row.getDeliveryDate() + " / " + row.getQuotaDate(),
                "命中盒数（期望 1）", row.getBoxes(),
                "配送任务是否存在（期望 存在）", row.getTaskNo() == null ? "无" : "存在");

        assertThat(row.getQuotaDate()).isEqualTo(poolDate);
        assertThat(row.getDeliveryDate()).isEqualTo(deliveryDate);
        assertThat(row.getBoxes()).isEqualTo(1);
        // 配送任务按订单自身区间展开，故按配送日命中（这正是两者必须分列的原因）
        assertThat(row.getTaskNo()).isNotNull();
        assertThat(row.getOrderId()).isEqualTo(orderId);
    }

    private void createBatch(String batchNo, LocalDate arrivalDate) {
        ProductBatch batch = new ProductBatch();
        batch.setBatchNo(batchNo);
        batch.setProductId(productId);
        batch.setProductionDate(arrivalDate.minusDays(2));
        batch.setArrivalDate(arrivalDate);
        batch.setStatus(1);
        batch.setRemark(TAG + "批次实验");
        productBatchService.createBatch(batch);
    }

    /** 设置某日配额并把池子标注到指定批号 */
    private void tagQuotaWithBatch(LocalDate date, int totalQuota, String batchNo) {
        DailyQuotaBatchRequest.Item item = new DailyQuotaBatchRequest.Item();
        item.setProductId(productId);
        item.setTotalQuota(totalQuota);
        item.setBatchNo(batchNo);
        dailyQuotaService.setQuotaBatch(date, List.of(item), TAG + "批次标注");
    }
}
