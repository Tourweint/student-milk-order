package com.milk.order.module.nutrition.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 营养摄入按日汇总视图对象
 */
@Data
public class NutritionSummaryVO implements Serializable {

    /** 摄入日期 */
    private LocalDate intakeDate;

    /** 总摄入 ml 数 */
    private Integer totalMl;

    /** 总能量（千焦） */
    private BigDecimal totalEnergy;

    /** 总蛋白质（克） */
    private BigDecimal totalProtein;

    /** 总脂肪（克） */
    private BigDecimal totalFat;

    /** 总钙（毫克） */
    private BigDecimal totalCalcium;

    /** 摄入奶品种类数 */
    private Integer productCount;
}
