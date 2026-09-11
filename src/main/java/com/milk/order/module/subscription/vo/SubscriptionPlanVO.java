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
        vo.setStatusText(plan.getStatus() == 1 ? "已开启" : "已关闭");
        vo.setNextRenewalTime(plan.getNextRenewalTime());
        vo.setLastRenewalTime(plan.getLastRenewalTime());
        vo.setRemark(plan.getRemark());
        vo.setCreateTime(plan.getCreateTime());
        return vo;
    }
}
