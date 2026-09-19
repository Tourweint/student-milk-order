package com.milk.order.process.invariant;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 一次不变量违规的检出结果（只读探测的产物，不含任何修复副作用）。
 */
@Getter
@Builder
public class InvariantViolation {

    /** 不变量编码 */
    private final String code;

    /** 处置等级 */
    private final InvariantSeverity severity;

    /** 违规主体表名 */
    private final String entityType;

    /** 违规主体主键 */
    private final Long entityId;

    /** 业务单号（便于人工定位） */
    private final String bizNo;

    /** 违规明细：应写明「期望值 vs 实际值」，这是体检报告可读性的关键 */
    private final String detail;

    /** 修复所需的上下文（如台账期望值），由修复器读取 */
    @Builder.Default
    private final Map<String, Object> attributes = Map.of();

    /** 读取修复上下文 */
    public Object attr(String key) {
        return attributes.get(key);
    }

    /** 读取整型修复上下文 */
    public Integer intAttr(String key) {
        Object value = attributes.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 读取字符串修复上下文 */
    public String strAttr(String key) {
        Object value = attributes.get(key);
        return value == null ? null : value.toString();
    }
}
