package com.milk.order.module.delivery.vo;

import com.milk.order.module.delivery.entity.DeliveryRecord;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配送记录视图对象（回填班级/学生/奶品/任务编号+签收状态文本）
 */
@Data
public class DeliveryRecordVO implements Serializable {

    private Long id;
    private Long taskId;
    private String taskNo;
    private LocalDate deliveryDate;
    private Long classId;
    private String className;
    private Long studentId;
    private String studentName;
    private Long productId;
    private String productName;
    private String spec;
    private Integer quantity;
    private Integer signStatus;
    private String signStatusText;
    private LocalDateTime signTime;
    private String signPerson;
    private String remark;
    private LocalDateTime createTime;

    public static DeliveryRecordVO from(DeliveryRecord r, String taskNo, LocalDate deliveryDate,
                                         Long classId, String className, String studentName, String productName,
                                         String spec, String signStatusText) {
        DeliveryRecordVO vo = new DeliveryRecordVO();
        vo.setId(r.getId());
        vo.setTaskId(r.getTaskId());
        vo.setTaskNo(taskNo);
        vo.setDeliveryDate(deliveryDate);
        vo.setClassId(classId);
        vo.setClassName(className);
        vo.setStudentId(r.getStudentId());
        vo.setStudentName(studentName);
        vo.setProductId(r.getProductId());
        vo.setProductName(productName);
        vo.setSpec(spec);
        vo.setQuantity(r.getQuantity());
        vo.setSignStatus(r.getSignStatus());
        vo.setSignStatusText(signStatusText);
        vo.setSignTime(r.getSignTime());
        vo.setSignPerson(r.getSignPerson());
        vo.setRemark(r.getRemark());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }
}
