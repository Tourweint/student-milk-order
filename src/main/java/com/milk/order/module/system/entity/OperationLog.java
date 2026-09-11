package com.milk.order.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志实体
 */
@Data
@TableName("operation_log")
public class OperationLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户ID */
    private Long userId;

    /** 操作用户名 */
    private String username;

    /** 操作描述 */
    private String operation;

    /** 请求方法（类名.方法名） */
    private String method;

    /** 请求URL */
    private String requestUrl;

    /** HTTP方法 */
    private String requestMethod;

    /** 请求参数（JSON） */
    private String requestParams;

    /** 操作IP */
    private String ip;

    /** 耗时（毫秒） */
    private Long costTime;

    /** 操作状态：0-失败，1-成功 */
    private Integer status;

    /** 异常信息 */
    private String errorMsg;

    /** 操作时间 */
    private LocalDateTime createTime;
}
