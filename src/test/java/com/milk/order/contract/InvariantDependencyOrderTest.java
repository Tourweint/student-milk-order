package com.milk.order.contract;

import com.milk.order.experiment.ExperimentSupport;
import com.milk.order.module.delivery.invariant.OrderTaskConsistencyInvariant;
import com.milk.order.module.order.invariant.OrderAggregationInvariant;
import com.milk.order.module.order.invariant.ParentTerminalChildPendingInvariant;
import com.milk.order.process.invariant.ProcessInvariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约矩阵测试：不变量执行顺序由**声明的依赖**决定。
 *
 * <p>为什么这是契约而不是实验：不变量之间不是彼此独立的，修复动作会互相影响。
 * 典型是「子过程网格补全」与「父过程聚合」——父聚合的判定依据是"子任务是否全部终态"，
 * 若先判父、再补子，父状态就会建立在"当时完整、随后被补"的子集合上，
 * 于是**体检自己制造出**「已完成订单 + 待配送任务」这种组合异常。
 *
 * <p>因此本测试把顺序钉死：① 每条声明依赖都必须排在本项之前（拓扑性）；
 * ② 三个已知的关键序对成立；③ 顺序确定可复现；④ 它**不是字典序的巧合**——
 * 字典序下 `INV_ORDER_AGGREGATION` 会排在 `INV_ORDER_TASK` 之前，
 * 所以"顺序正确"这件事本身证明依赖声明在起作用。</p>
 */
@DisplayName("契约矩阵测试：不变量执行顺序由声明依赖决定")
class InvariantDependencyOrderTest extends ExperimentSupport {

    @Autowired
    private List<ProcessInvariant> allInvariants;

    @Test
    @DisplayName("执行顺序满足全部声明依赖、关键序对成立、且不是字典序的巧合")
    void executionOrderRespectsDeclaredDependencies() {
        List<String> order = invariantScanner.resolvedExecutionOrder();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }

        List<String> broken = new ArrayList<>();
        for (ProcessInvariant invariant : allInvariants) {
            for (String dependency : invariant.dependsOn()) {
                Integer selfPos = index.get(invariant.code());
                Integer depPos = index.get(dependency);
                if (selfPos == null || depPos == null || depPos > selfPos) {
                    broken.add(invariant.code() + " 声明依赖 " + dependency + "，但执行顺序不满足");
                }
            }
        }

        int taskPos = index.getOrDefault(OrderTaskConsistencyInvariant.CODE, -1);
        int aggregationPos = index.getOrDefault(OrderAggregationInvariant.CODE, -1);
        int guardPos = index.getOrDefault(ParentTerminalChildPendingInvariant.CODE, -1);
        List<String> alphabetical = order.stream().sorted().toList();
        List<String> secondCall = invariantScanner.resolvedExecutionOrder();

        report("契约矩阵 · 不变量执行顺序",
                "执行顺序", String.join(" → ", order),
                "字典序（对照）", String.join(" → ", alphabetical),
                "是否等于字典序（应为 false，否则依赖声明没起作用）", order.equals(alphabetical),
                "网格补全 位于父聚合之前", taskPos + " < " + aggregationPos,
                "父聚合 位于组合守卫之前", aggregationPos + " < " + guardPos,
                "两次解析结果一致（确定性）", secondCall.equals(order),
                "违反声明依赖的项（应无）", broken.isEmpty() ? "（无）" : broken);

        assertThat(broken).isEmpty();
        assertThat(order).containsAll(allInvariants.stream().map(ProcessInvariant::code).toList());
        assertThat(order).doesNotHaveDuplicates();

        // 关键序对：先补子过程网格 → 再判父聚合 → 最后判组合守卫
        assertThat(taskPos).isLessThan(aggregationPos);
        assertThat(aggregationPos).isLessThan(guardPos);

        // 声明确实改变了顺序（字典序会得到相反的前两项），因此这不是命名巧合
        assertThat(order).isNotEqualTo(alphabetical);
        assertThat(secondCall).isEqualTo(order);
    }
}
