package com.milk.order.module.nutrition.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 营养摄入记录表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nutrition_intake")
public class NutritionIntake extends BaseEntity {

    /** 学生 ID */
    private Long studentId;

    /** 摄入日期 */
    private LocalDate intakeDate;

    /** 奶品 ID */
    private Long productId;

    /** 摄入数量（ml） */
    private Integer quantity;

    /** 能量摄入（千焦） */
    private BigDecimal energy;

    /** 蛋白质摄入（克） */
    private BigDecimal protein;

    /** 脂肪摄入（克） */
    private BigDecimal fat;

    /** 钙摄入（毫克） */
    private BigDecimal calcium;

    /** 关联配送记录 ID */
    private Long deliveryRecordId;
}
