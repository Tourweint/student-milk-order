package com.milk.order.module.order.process;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.common.constant.StateTransitions;
import com.milk.order.module.delivery.service.DeliveryTaskService;
import com.milk.order.module.order.entity.OrderInfo;
import com.milk.order.module.order.mapper.OrderInfoMapper;
import com.milk.order.process.reconcile.ChildProcessCondition;
import com.milk.order.process.reconcile.ParentProcessProbe;
import com.milk.order.process.reconcile.ProcessCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单过程的父过程探测器：把「怎么查候选订单」与「子任务集合满足什么条件」交给订单侧实现。
 *
 * <p>过程层因此不需要认识 order_info / delivery_task 任何一张表，
 * “父过程”这个概念对过程层而言只是一个接口。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderProcessProbe implements ParentProcessProbe {

    private static final String ENTITY_TYPE = "order_info";

    private final OrderInfoMapper orderInfoMapper;
    private final DeliveryTaskService deliveryTaskService;

    @Override
    public String scene() {
        return StateTransitions.SCENE_ORDER;
    }

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public ProcessCandidate find(Long parentId) {
        OrderInfo order = orderInfoMapper.selectById(parentId);
        return order == null ? null : toCandidate(order);
    }

    @Override
    public List<ProcessCandidate> candidatesByStatus(Integer status, int limit) {
        return orderInfoMapper.selectList(new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getStatus, status)
                        .orderByAsc(OrderInfo::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(this::toCandidate)
                .toList();
    }

    /**
     * 子过程条件求值。
     *
     * <p>注意 {@code ALL_TASKS_TERMINAL} 要求「至少有一条子任务」：
     * 若订单一条任务都没有，那是任务生成缺失（另一条不变量负责），不能据此把订单判成已完成。</p>
     */
    @Override
    public boolean evaluateChildCondition(ChildProcessCondition condition, Long parentId) {
        return switch (condition) {
            case NONE -> !deliveryTaskService.hasAnyTask(parentId);
            case HAS_DISPATCHING_TASK -> deliveryTaskService.hasDispatchingTask(parentId);
            case ALL_TASKS_TERMINAL -> deliveryTaskService.hasAnyTask(parentId)
                    && !deliveryTaskService.hasUnfinishedTask(parentId);
            case ALL_TASKS_CANCELLED -> deliveryTaskService.hasAllTasksCancelled(parentId);
        };
    }

    private ProcessCandidate toCandidate(OrderInfo order) {
        return ProcessCandidate.builder()
                .id(order.getId())
                .bizNo(order.getOrderNo())
                .status(order.getStatus())
                .entityType(ENTITY_TYPE)
                .build();
    }
}
