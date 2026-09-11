package com.milk.order.module.clazz.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 批量导入结果
 */
@Data
public class ImportResultVO implements Serializable {

    /** 成功条数 */
    private Integer successCount = 0;

    /** 失败条数 */
    private Integer failCount = 0;

    /** 失败明细（行号 + 原因） */
    private List<String> errors = new ArrayList<>();

    public void addSuccess() {
        this.successCount++;
    }

    public void addError(String message) {
        this.failCount++;
        this.errors.add(message);
    }
}
