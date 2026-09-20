package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 配送日平移结果统计。
 */
@Data
public class ShiftResultVO implements Serializable {

    /** 请求平移的任务条数 */
    private int requested;

    /** 成功平移条数 */
    private int shifted;

    /** 其中：合并到目标日已有任务 */
    private int merged;

    /** 其中：在目标日新建任务 */
    private int created;

    /** 跳过条数（非待配送、并发已被处理等） */
    private int skipped;

    /** 逐条说明（跳过/失败原因） */
    private List<String> messages = new ArrayList<>();
}
