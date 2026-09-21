package com.milk.order.module.clazz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.milk.order.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学生表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student")
public class Student extends BaseEntity {

    /** 学号 */
    private String studentNo;

    /** 学生姓名 */
    private String studentName;

    /** 性别：0-女，1-男 */
    private Integer gender;

    /** 班级 ID */
    private Long classId;

    /** 家长用户 ID */
    private Long parentId;

    /** 家长姓名 */
    private String parentName;

    /** 家长电话 */
    private String parentPhone;

    /** 出生日期 */
    private java.time.LocalDate birthDate;

    /**
     * 过敏/禁忌标签（逗号分隔的受控编码，取值来自 {@link com.milk.order.common.enums.AllergenType}）。
     *
     * <p>与奶品表的 `allergen_tags` **共用同一套编码**——下单预检的命中判定是两者交集，
     * 若两侧自由文本各写各的（"乳糖不耐" vs "乳糖"），警示会静默失效。写入时经
     * {@code AllergenType.normalizeStrict} 统一规范化。</p>
     */
    private String allergyTags;

    /** 备注 */
    private String remark;
}
