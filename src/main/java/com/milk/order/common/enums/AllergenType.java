package com.milk.order.common.enums;

import com.milk.order.exception.BusinessException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 过敏原受控枚举（学生禁忌与奶品过敏原**共用同一套编码**）
 *
 * <p><b>为什么要"受控枚举"而不是自由文本</b>：命中判定是"学生禁忌 ∩ 奶品过敏原"。
 * 若两边各写各的（学生写"乳糖不耐"、奶品写"乳糖"），字符串永远匹配不上，警示会静默失效
 * ——这类"看起来配了、其实没生效"的配置最危险。因此两边都从本枚举取值，
 * 写入时统一规范化（大写、去空白、去重、拒绝未知编码），读出时按编码映射中文文案。</p>
 *
 * <p>两侧文案分开：奶品侧是成分（"乳糖"），学生侧是禁忌说法（"乳糖不耐"），
 * 由 {@link #getStudentText()} 提供，避免前端各自硬编码一套中文。</p>
 */
public enum AllergenType {

    /** 乳糖（学生侧说法：乳糖不耐） */
    LACTOSE("乳糖", "乳糖不耐"),
    /** 坚果（树坚果：核桃、腰果等） */
    NUTS("坚果", "坚果过敏"),
    /** 花生 */
    PEANUT("花生", "花生过敏"),
    /** 大豆 */
    SOY("大豆", "大豆过敏"),
    /** 香精 */
    FLAVORING("香精", "香精敏感"),
    /** 蛋类 */
    EGG("蛋类", "蛋类过敏"),
    /** 其他（未细分的禁忌，需线下确认） */
    OTHER("其他", "其他禁忌");

    private final String text;
    private final String studentText;

    AllergenType(String text, String studentText) {
        this.text = text;
        this.studentText = studentText;
    }

    /** 奶品侧文案（成分） */
    public String getText() {
        return text;
    }

    /** 学生侧文案（禁忌说法） */
    public String getStudentText() {
        return studentText;
    }

    /** 全部编码（供管理端/小程序渲染选项，跨端共用同一份"真理来源"） */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(AllergenType::name).collect(Collectors.toList());
    }

    /** 编码 → 奶品侧文案；未知编码返回 null */
    public static AllergenType of(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim().toUpperCase();
        for (AllergenType type : values()) {
            if (type.name().equals(trimmed)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 宽松解析已存库的逗号分隔编码（忽略未知编码与空项，保序去重）。
     *
     * <p>用于"读"：历史数据里可能有旧编码，读侧不应因此报错。</p>
     */
    public static Set<String> parse(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String piece : tags.split(",")) {
            AllergenType type = of(piece);
            if (type != null) {
                result.add(type.name());
            }
        }
        return result;
    }

    /**
     * 严格规范化待写库的编码串：统一大写、去空白、去重、**未知编码直接拒绝**。
     *
     * <p>用于"写"：宁可在保存时报错，也不要让一个拼错的编码静默躺在库里、
     * 让警示"看起来配了、其实永不命中"。</p>
     *
     * <p><b>空输入返回空串而不是 {@code null}</b>：MyBatis-Plus 默认只更新非 null 字段，
     * 若"清空标签"传成 null 会被静默跳过、清不掉（用户看到"保存成功"但标签还在）。
     * 返回空串使"显式清空"能真正落库。</p>
     */
    public static String normalizeStrict(String tags) {
        Set<String> codes = new LinkedHashSet<>();
        if (tags != null) {
            for (String piece : tags.split(",")) {
                if (piece.trim().isEmpty()) {
                    continue;
                }
                AllergenType type = of(piece);
                if (type == null) {
                    throw new BusinessException("未知的过敏原编码「" + piece.trim() + "」，可选："
                            + String.join("/", allCodes()));
                }
                codes.add(type.name());
            }
        }
        return String.join(",", codes);
    }

    /** 编码集合 → 逗号分隔串（保持传入顺序；空集合返回空串，语义同 {@link #normalizeStrict}） */
    public static String join(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        return String.join(",", codes);
    }

    /** 编码 → 中文（奶品侧文案），未知返回原编码 */
    public static String textOf(String code) {
        AllergenType type = of(code);
        return type == null ? code : type.getText();
    }
}
