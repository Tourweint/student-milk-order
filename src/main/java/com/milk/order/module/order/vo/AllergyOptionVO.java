package com.milk.order.module.order.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 过敏原选项（受控枚举的对外投影，学生侧/奶品侧共用一套编码）
 *
 * <p>由后端下发而不是两端各自硬编码：编码是"匹配的依据"，只有一份才不会出现
 * "学生写乳糖不耐、奶品写乳糖"这种永不命中的错配。</p>
 */
@Data
public class AllergyOptionVO implements Serializable {

    /** 编码（如 LACTOSE） */
    private String code;

    /** 奶品侧文案（如 乳糖） */
    private String text;

    /** 学生侧文案（如 乳糖不耐） */
    private String studentText;
}
