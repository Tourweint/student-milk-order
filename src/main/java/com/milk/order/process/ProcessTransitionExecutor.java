package com.milk.order.process;

import java.util.function.BooleanSupplier;

/**
 * 业务过程状态迁移统一执行器（过程层 / 可靠性层的共同出口）。
 *
 * <p>把原先散落在各 Service 中的三件事收口到一处：</p>
 * <ol>
 *   <li><b>规则校验</b>：迁移是否允许由 state_transition_rule 规则表（白名单）驱动，管理端可在线调整；</li>
 *   <li><b>CAS 条件更新</b>：调用方以「原状态」为条件更新目标状态，并发下只有一个执行者成功；</li>
 *   <li><b>迁移留痕</b>：成功与冲突都写入 process_transition_log 迁移台账，形成可回溯的过程记录。</li>
 * </ol>
 *
 * <p>业务不变量：业务对象的 status 字段只能经本执行器完成迁移，
 * 禁止在 Controller 或业务 Service 中直接以非条件更新方式修改状态。</p>
 */
public interface ProcessTransitionExecutor {

    /**
     * 严格迁移：规则禁止抛业务异常；CAS 冲突抛业务异常（携带 conflictMessage）。
     *
     * <p>用于用户直接发起、失败必须明确告知的操作（支付、退订、手动完成、任务取消等）。</p>
     *
     * @param spec      迁移规格
     * @param casUpdate 真正执行条件更新的动作，返回 true 表示本次迁移生效
     */
    void require(TransitionSpec spec, BooleanSupplier casUpdate);

    /**
     * 宽松迁移：规则禁止或 CAS 冲突都返回 false，不抛异常。
     *
     * <p>用于批量流转、定时兜底、对账补偿等「本轮不生效即跳过」的场景，
     * 天然具备幂等性：重复执行不会产生副作用。</p>
     *
     * @return true 表示本次迁移生效
     */
    boolean attempt(TransitionSpec spec, BooleanSupplier casUpdate);

    /** 只查询迁移是否允许（不执行），用于回调幂等分支等需要自行分流的场景 */
    boolean allowed(String scene, String action, Integer fromStatus);

    /** 只断言迁移允许（不执行），用于批量操作的前置一次性校验 */
    void requireAllowed(String scene, String action, Integer fromStatus, String sceneText);
}
