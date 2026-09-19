package com.milk.order.module.order.invariant;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.process.invariant.InvariantSeverity;
import com.milk.order.process.invariant.InvariantViolation;
import com.milk.order.process.invariant.ProcessInvariant;
import com.milk.order.process.reconcile.ProcessReconcileCoordinator;
import com.milk.order.process.reconcile.ReconcileDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不变量 INV_ORDER_AGGREGATION：父订单状态必须与子任务集合一致
 * （存在配送中任务则订单至少为配送中；任务全终态则订单为已完成）。
 *
 * <p>它是原「过程聚合对账」能力在体检体系中的登记项：探测复用补偿探测器（只读），
 * 修复复用补偿协调器（经规则表决策 + 统一迁移出口执行），因此两者不会出现两套判定口径。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderAggregationInvariant implements ProcessInvariant {

    public static final String CODE = "INV_ORDER_AGGREGATION";
    private static final String ENTITY_TYPE = "order_info";

    private final ProcessReconcileCoordinator reconcileCoordinator;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "父订单状态与子任务集合一致：存在配送中任务则订单已进入配送中，任务全终态则订单已完成";
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.AUTO_REPAIR;
    }

    @Override
    public List<InvariantViolation> detect(int limit) {
        return reconcileCoordinator.detect(StateTransitions.SCENE_ORDER, limit).stream()
                .map(this::toViolation)
                .toList();
    }

    @Override
    public boolean repair(InvariantViolation violation) {
        // 修复时重新探测（见 ProcessReconcileCoordinator.reconcileEntity）：从检出到修复之间
        // 父状态可能已被正常路径推进，此时补偿应当什么都不做而不是按旧判定强推
        return reconcileCoordinator.reconcileEntity(StateTransitions.SCENE_ORDER, violation.getEntityId())
                .isRepaired();
    }

    private InvariantViolation toViolation(ReconcileDecision decision) {
        return InvariantViolation.builder()
                .code(CODE)
                .severity(InvariantSeverity.AUTO_REPAIR)
                .entityType(ENTITY_TYPE)
                .entityId(decision.getCandidate().getId())
                .bizNo(decision.getCandidate().getBizNo())
                .detail(String.format("命中补偿规则「%s」（子过程条件 %s）：期望 %d → %d，实际父状态 %d",
                        decision.getRule().getName(), decision.getRule().getChildCondition(),
                        decision.getCandidate().getStatus(), decision.getRule().getTargetStatus(),
                        decision.getCandidate().getStatus()))
                .build();
    }
}
