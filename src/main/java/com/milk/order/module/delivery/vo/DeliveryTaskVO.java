package com.milk.order.module.delivery.vo;

import com.milk.order.module.delivery.entity.DeliveryTask;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配送任务视图对象（回填班级/学生/奶品/订单号+状态文本）
 */
@Data
public class DeliveryTaskVO implements Serializable {

    private Long id;
    private String taskNo;
    private LocalDate deliveryDate;
    private Long classId;
    private String className;
    private Long orderId;
    private String orderNo;
    private Long studentId;
    private String studentName;
    private Long productId;
    private String productName;
    private String spec;
    private Integer quantity;
    private Integer status;
    private String statusText;
    private String remark;
    private LocalDateTime createTime;

    public static DeliveryTaskVO from(DeliveryTask t, String className, String orderNo,
                                       String studentName, String productName, String spec, String statusText) {
        DeliveryTaskVO vo = new DeliveryTaskVO();
        vo.setId(t.getId());
        vo.setTaskNo(t.getTaskNo());
        vo.setDeliveryDate(t.getDeliveryDate());
        vo.setClassId(t.getClassId());
        vo.setClassName(className);
        vo.setOrderId(t.getOrderId());
        vo.setOrderNo(orderNo);
        vo.setStudentId(t.getStudentId());
        vo.setStudentName(studentName);
        vo.setProductId(t.getProductId());
        vo.setProductName(productName);
        vo.setSpec(spec);
        vo.setQuantity(t.getQuantity());
        vo.setStatus(t.getStatus());
        vo.setStatusText(statusText);
        vo.setRemark(t.getRemark());
        vo.setCreateTime(t.getCreateTime());
        return vo;
    }
}
