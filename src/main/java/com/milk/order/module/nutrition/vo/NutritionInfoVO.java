package com.milk.order.module.nutrition.vo;

import com.milk.order.module.nutrition.entity.NutritionInfo;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 营养成分视图对象（回填奶品名/规格）
 */
@Data
public class NutritionInfoVO implements Serializable {

    private Long id;
    private Long productId;
    private String productName;
    private String spec;
    private BigDecimal energy;
    private BigDecimal protein;
    private BigDecimal fat;
    private BigDecimal carbohydrate;
    private BigDecimal calcium;
    private BigDecimal sodium;
    private String remark;

    public static NutritionInfoVO from(NutritionInfo info, String productName, String spec) {
        NutritionInfoVO vo = new NutritionInfoVO();
        vo.setId(info.getId());
        vo.setProductId(info.getProductId());
        vo.setProductName(productName);
        vo.setSpec(spec);
        vo.setEnergy(info.getEnergy());
        vo.setProtein(info.getProtein());
        vo.setFat(info.getFat());
        vo.setCarbohydrate(info.getCarbohydrate());
        vo.setCalcium(info.getCalcium());
        vo.setSodium(info.getSodium());
        vo.setRemark(info.getRemark());
        return vo;
    }
}
