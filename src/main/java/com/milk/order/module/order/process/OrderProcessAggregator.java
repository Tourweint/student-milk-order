package com.milk.order.module.order.process;

import com.milk.order.common.constant.StateTransitions;
import com.milk.order.module.order.service.OrderInfoService;
import com.milk.order.process.reconcile.ParentProcessAggregator;
import com.milk.order.process.reconcile.ProcessCandidate;
import com.milk.order.process.reconcile.ProcessReconcileRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 订单过程的聚合器：把补偿规则声明的动作落到订单状态上。
 *
 * <p>所有动作都复用订单侧的聚合出口（其内部经统一迁移出口执行条件更新并留痕），
 * 因此补偿天然幂等、天然留痕，不会成为绕过可靠性层的旁路。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderProcessAggregator implements ParentProcessAggregator {

    private final OrderInfoService orderInfoService;

    @Override
    public String scene() {
        return StateTransitions.SCENE_ORDER;
    }

    @Override
    public boolean compensate(ProcessReconcileRule rule, ProcessCandidate parent) {
        return switch (rule.getAction()) {
            case StateTransitions.ACTION_DELIVER -> orderInfoService.markDeliveringIfPaid(parent.getId());
            case StateTransitions.ACTION_AUTO_COMPLETE -> orderInfoService.completeOrderIfAllTasksDone(parent.getId());
            // 明确失败而不是静默跳过：规则表配出的动作若没有实现，必须立刻可见，
            // 否则“配了规则却永远修不好”将成为新的隐形故障
            default -> throw new IllegalStateException(
                    "订单过程未实现补偿动作：" + rule.getAction() + "（规则「" + rule.getName() + "」）");
        };
    }
}
