package com.milk.order.module.auth.vo;

import lombok.Data;

/**
 * 微信 code2session 接口响应（jscode2session）
 * 真实返回含 openid/session_key/unionid/errcode/errmsg
 */
@Data
public class WxSessionVO {

    /** 用户唯一标识（同一小程序内唯一） */
    private String openid;

    /** 会话密钥 */
    private String sessionKey;

    /** 用户在开放平台的唯一标识（需绑定开放平台才有） */
    private String unionid;

    /** 错误码：0 为成功 */
    private Integer errcode;

    /** 错误信息 */
    private String errmsg;
}
