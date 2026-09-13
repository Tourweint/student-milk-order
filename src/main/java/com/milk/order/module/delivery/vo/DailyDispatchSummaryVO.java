package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 某配送日期按班级汇总视图对象（配送站面板今日概览）
 */
@Data
public class DailyDispatchSummaryVO implements Serializable {

    private Long classId;

    private String className;

    /** 应送任务总数 */
    private Integer total;

    /** 待配送 */
    private Integer pending;

    /** 配送中（已送出） */
    private Integer dispatching;

    /** 已完成（已签收） */
    private Integer completed;

    /** 已取消 */
    private Integer cancelled;
}
