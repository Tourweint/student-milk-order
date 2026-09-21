package com.milk.order.module.order.service;

import com.milk.order.module.order.vo.AllergyOptionVO;
import com.milk.order.module.order.vo.AllergyWarningVO;

import java.util.Collection;
import java.util.List;

/**
 * 过敏/禁忌软警示服务（下单前预检）
 *
 * <p>只读、无副作用、**不拦截下单**：命中即返回提示，由用户确认后继续。</p>
 */
public interface AllergyWarningService {

    /** 受控过敏原选项（学生侧与奶品侧共用；含两侧文案） */
    List<AllergyOptionVO> options();

    /**
     * 下单前预检：学生禁忌标签 ∩ 奶品过敏原标签非空即命中。
     *
     * @param studentId  下单学生（数据范围校验与下单同口径：家长仅本人孩子、班主任仅本班）
     * @param productIds 本次下单涉及的奶品
     * @return 命中清单（空列表 = 无警示）
     */
    List<AllergyWarningVO> check(Long studentId, Collection<Long> productIds);
}
