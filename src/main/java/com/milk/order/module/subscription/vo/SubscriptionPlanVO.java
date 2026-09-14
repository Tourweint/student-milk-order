package com.milk.order.module.subscription.vo;

import com.milk.order.module.subscription.entity.SubscriptionPlan;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 续订计划视图对象（回填学生/套餐/原订单号+状态文本）
 */
@Data
public class SubscriptionPlanVO implements Serializable {

    private Long id;
    private Long studentId;
    private String studentName;
    private Long userId;
    private Long packageId;
    private String packageName;
    private Long originalOrderId;
    private String originalOrderNo;
    private Integer cycleType;
    private String cycleTypeText;
    private Integer status;
    private String statusText;
    private LocalDateTime nextRenewalTime;
    private LocalDateTime lastRenewalTime;
    private LocalDateTime pauseTime;
    private String pauseReason;
    private String remark;
    private LocalDateTime createTime;

    public static SubscriptionPlanVO from(SubscriptionPlan plan, String studentName,
                                           String packageName, String originalOrderNo) {
        SubscriptionPlanVO vo = new SubscriptionPlanVO();
        vo.setId(plan.getId());
        vo.setStudentId(plan.getStudentId());
        vo.setStudentName(studentName);
        vo.setUserId(plan.getUserId());
        vo.setPackageId(plan.getPackageId());
        vo.setPackageName(packageName);
        vo.setOriginalOrderId(plan.getOriginalOrderId());
        vo.setOriginalOrderNo(originalOrderNo);
        vo.setCycleType(plan.getCycleType());
        vo.setCycleTypeText(plan.getCycleType() == 1 ? "每月续订" : "未知");
        vo.setStatus(plan.getStatus());
        vo.setStatusText(statusText(plan.getStatus()));
        vo.setNextRenewalTime(plan.getNextRenewalTime());
        vo.setLastRenewalTime(plan.getLastRenewalTime());
        vo.setPauseTime(plan.getPauseTime());
        vo.setPauseReason(plan.getPauseReason());
        vo.setRemark(plan.getRemark());
        vo.setCreateTime(plan.getCreateTime());
        return vo;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0:
                return "已关闭";
            case 1:
                return "已开启";
            case 2:
                return "已暂停";
            default:
                return "未知";
        }
    }
}
