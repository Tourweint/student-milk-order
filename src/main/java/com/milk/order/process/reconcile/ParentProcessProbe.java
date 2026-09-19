package com.milk.order.process.reconcile;

import java.util.List;

/**
 * 父过程探测器（各业务模块实现并注册，过程层不认识任何业务表）。
 *
 * <p>把「候选怎么查」与「子过程条件怎么判」交给业务模块实现，过程层只负责编排：
 * 按规则表的 parent_status 取候选 → 逐条求值子过程条件 → 产出决策。
 * 这样新增一类父过程（如续订计划）只需新增一个探测器 + 一个聚合器，过程层零改动。</p>
 */
public interface ParentProcessProbe {

    /** 父过程场景，见 StateTransitions.SCENE_* */
    String scene();

    /** 父过程所在表名 */
    String entityType();

    /** 按主键取单个候选（实时通道精确补偿用）；查不到返回 null */
    ProcessCandidate find(Long parentId);

    /** 按父状态取一批候选（兜底通道批量探测用，必须限批且顺序稳定） */
    List<ProcessCandidate> candidatesByStatus(Integer status, int limit);

    /**
     * 求值子过程条件。
     *
     * @param condition 条件编码（已由过程层解析为枚举）
     * @param parentId  父过程主键
     * @return true 表示条件成立
     */
    boolean evaluateChildCondition(ChildProcessCondition condition, Long parentId);
}
