package com.milk.order.module.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.process.ProcessTransitionLog;

/**
 * 过程迁移台账服务（只读）。
 *
 * <p>台账由过程层 {@code ProcessTransitionExecutor} 在每次状态迁移时写入，
 * 本服务只提供管理端的查询能力，用于「过程回放 / 问题回溯 / 父子状态对账取证」。
 * 台账不提供修改与删除：它是已提交业务事实的记录，任何删改都会破坏可追溯性。</p>
 */
public interface ProcessTransitionLogService extends IService<ProcessTransitionLog> {

    /**
     * 迁移台账分页查询。
     *
     * @param scene      场景（ORDER / DELIVERY_TASK / DELIVERY_RECORD），可空
     * @param action     动作编码（PAY / CANCEL / DISPATCH / SIGN / REJECT ...），可空
     * @param entityType 迁移主体表名，可空
     * @param bizNo      业务单号（订单号 / 任务号），模糊匹配，可空
     * @param result     迁移结果（1-已生效，0-CAS 冲突未生效），可空
     * @param startTime  记录时间起（yyyy-MM-dd HH:mm:ss），可空
     * @param endTime    记录时间止（yyyy-MM-dd HH:mm:ss），可空
     */
    IPage<ProcessTransitionLog> pageLogs(Long pageNum, Long pageSize, String scene, String action,
                                         String entityType, String bizNo, Integer result,
                                         String startTime, String endTime);
}
