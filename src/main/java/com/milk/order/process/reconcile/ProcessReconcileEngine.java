package com.milk.order.process.reconcile;

import java.util.List;

/**
 * 过程补偿探测器：按规则表发现「父过程状态与子过程集合不一致」的漂移。
 *
 * <p><b>只读契约：</b>本接口的所有方法都不得修改任何业务状态——探测与执行严格分离，
 * 使“发现漂移”可以被随时调用（体检、预览、实验、实时通道）而不产生副作用。</p>
 */
public interface ProcessReconcileEngine {

    /**
     * 批量探测：扫描该场景下规则表涉及的全部父状态，返回命中的补偿决策。
     *
     * <p>同一主体一轮最多产出一条决策（按规则 id 顺序取首个命中），
     * 避免多条规则叠加导致一次补偿跨多个状态。</p>
     *
     * @param parentScene 父过程场景
     * @param limit       每个父状态最多取多少候选
     */
    List<ReconcileDecision> detect(String parentScene, int limit);

    /** 单主体探测（实时通道用）：按最新状态判定该主体是否需要补偿，不需要则返回空列表 */
    List<ReconcileDecision> detectEntity(String parentScene, Long parentId);
}
