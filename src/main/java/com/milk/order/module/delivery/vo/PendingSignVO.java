package com.milk.order.module.delivery.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 某配送日期「已送出未签收」待签收汇总视图对象
 *
 * 供配送站面板提醒班主任、班主任端一键签收入口与数据看板监控使用；
 * 班主任数据范围由 Service 层强制限定本班，管理员/配送站可看全部班级。
 */
@Data
public class PendingSignVO implements Serializable {

    /** 待签收记录总数（已送出任务的未签收配送记录数） */
    private Integer total;

    /** 按班级聚合的待签收明细 */
    private List<ClassPending> classes;

    @Data
    public static class ClassPending implements Serializable {

        /** 班级 ID */
        private Long classId;

        /** 班级名称 */
        private String className;

        /** 该班级待签收记录数 */
        private Integer count;
    }
}
