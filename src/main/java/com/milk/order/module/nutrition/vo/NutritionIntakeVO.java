package com.milk.order.module.nutrition.vo;

import com.milk.order.module.nutrition.entity.NutritionIntake;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 营养摄入记录视图对象（回填学生名/奶品名）
 */
@Data
public class NutritionIntakeVO implements Serializable {

    private Long id;
    private Long studentId;
    private String studentName;
    private LocalDate intakeDate;
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal energy;
    private BigDecimal protein;
    private BigDecimal fat;
    private BigDecimal calcium;
    private Long deliveryRecordId;
    private LocalDateTime createTime;

    public static NutritionIntakeVO from(NutritionIntake intake, String studentName, String productName) {
        NutritionIntakeVO vo = new NutritionIntakeVO();
        vo.setId(intake.getId());
        vo.setStudentId(intake.getStudentId());
        vo.setStudentName(studentName);
        vo.setIntakeDate(intake.getIntakeDate());
        vo.setProductId(intake.getProductId());
        vo.setProductName(productName);
        vo.setQuantity(intake.getQuantity());
        vo.setEnergy(intake.getEnergy());
        vo.setProtein(intake.getProtein());
        vo.setFat(intake.getFat());
        vo.setCalcium(intake.getCalcium());
        vo.setDeliveryRecordId(intake.getDeliveryRecordId());
        vo.setCreateTime(intake.getCreateTime());
        return vo;
    }
}
