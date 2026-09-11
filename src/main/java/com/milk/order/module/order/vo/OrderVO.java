package com.milk.order.module.order.vo;

import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.entity.OrderItem;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图对象（附带学生/班级/套餐/下单人名称、状态文本、明细）
 */
@Data
public class OrderVO implements Serializable {

    private Long id;

    private String orderNo;

    private Long studentId;

    /** 学生姓名 */
    private String studentName;

    private Long userId;

    /** 下单人姓名 */
    private String userName;

    private Long classId;

    /** 班级名称 */
    private String className;

    private Long packageId;

    /** 套餐名称 */
    private String packageName;

    /** 订单类型：1-按月，2-按学期 */
    private Integer orderType;

    /** 订单状态码 */
    private Integer status;

    /** 状态文本 */
    private String statusText;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    private BigDecimal discountAmount;

    private LocalDate deliveryStartDate;

    private LocalDate deliveryEndDate;

    private LocalDateTime payTime;

    /** 支付方式：1-模拟支付 */
    private Integer payType;

    /** 支付流水号 */
    private String transactionId;

    private LocalDateTime cancelTime;

    private String cancelReason;

    private String remark;

    private LocalDateTime createTime;

    /** 订单明细 */
    private List<OrderItem> items;

    public static OrderVO from(OrderInfo order, String studentName, String userName,
                               String className, String packageName, String statusText) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStudentId(order.getStudentId());
        vo.setStudentName(studentName);
        vo.setUserId(order.getUserId());
        vo.setUserName(userName);
        vo.setClassId(order.getClassId());
        vo.setClassName(className);
        vo.setPackageId(order.getPackageId());
        vo.setPackageName(packageName);
        vo.setOrderType(order.getOrderType());
        vo.setStatus(order.getStatus());
        vo.setStatusText(statusText);
        vo.setTotalAmount(order.getTotalAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setDeliveryStartDate(order.getDeliveryStartDate());
        vo.setDeliveryEndDate(order.getDeliveryEndDate());
        vo.setPayTime(order.getPayTime());
        vo.setPayType(order.getPayType());
        vo.setTransactionId(order.getTransactionId());
        vo.setCancelTime(order.getCancelTime());
        vo.setCancelReason(order.getCancelReason());
        vo.setRemark(order.getRemark());
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }
}
