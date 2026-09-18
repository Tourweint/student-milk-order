package com.milk.order.process;

import com.milk.order.common.utils.SecurityUtils;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.system.service.StateMachineService;
import com.milk.order.process.mapper.ProcessTransitionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;

/**
 * 状态迁移统一执行器实现：规则校验 → CAS 条件更新 → 迁移台账。
 *
 * <p>依赖方向：业务模块 → 过程层 → { 状态规则服务, 迁移台账 Mapper }。
 * 过程层不反向依赖任何业务 Service，因此可被订单、配送、订阅等模块共同复用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessTransitionExecutorImpl implements ProcessTransitionExecutor {

    /** 台账结果：迁移已生效 */
    public static final int RESULT_SUCCESS = 1;

    /** 台账结果：CAS 冲突，迁移未生效（并发下已被他方抢先） */
    public static final int RESULT_CONFLICT = 0;

    /** 无登录上下文时的操作人标识 */
    private static final String SYSTEM_OPERATOR = "system";

    private final StateMachineService stateMachineService;
    private final ProcessTransitionLogMapper transitionLogMapper;

    @Override
    public void require(TransitionSpec spec, BooleanSupplier casUpdate) {
        if (spec.isRuleGoverned()) {
            stateMachineService.assertAllowed(spec.getScene(), spec.getAction(),
                    spec.getFromStatus(), spec.getSceneText());
        }
        boolean updated = casUpdate.getAsBoolean();
        record(spec, updated ? RESULT_SUCCESS : RESULT_CONFLICT);
        if (!updated) {
            throw new BusinessException(conflictMessage(spec));
        }
    }

    @Override
    public boolean attempt(TransitionSpec spec, BooleanSupplier casUpdate) {
        if (spec.isRuleGoverned()
                && !stateMachineService.allowed(spec.getScene(), spec.getAction(), spec.getFromStatus())) {
            // 规则禁止：批量/兜底场景按“本轮不迁移”处理，静默返回。
            // 不写台账，避免管理端临时关闭规则时批量刷屏。
            log.debug("[过程迁移] 跳过 {} / {} / from={}：状态机规则禁止",
                    spec.getScene(), spec.getAction(), spec.getFromStatus());
            return false;
        }
        boolean updated = casUpdate.getAsBoolean();
        record(spec, updated ? RESULT_SUCCESS : RESULT_CONFLICT);
        return updated;
    }

    @Override
    public boolean allowed(String scene, String action, Integer fromStatus) {
        return stateMachineService.allowed(scene, action, fromStatus);
    }

    @Override
    public void requireAllowed(String scene, String action, Integer fromStatus, String sceneText) {
        stateMachineService.assertAllowed(scene, action, fromStatus, sceneText);
    }

    private String conflictMessage(TransitionSpec spec) {
        return StringUtils.hasText(spec.getConflictMessage())
                ? spec.getConflictMessage() : "状态已变更，请刷新后重试";
    }

    /**
     * 写迁移台账。台账是“尽力而为”的可观测记录：写入失败只告警，不得改变业务迁移结果，
     * 也不得让已提交的业务变更因台账问题而回滚。
     */
    private void record(TransitionSpec spec, int result) {
        try {
            ProcessTransitionLog entry = new ProcessTransitionLog();
            entry.setScene(spec.getScene());
            entry.setAction(spec.getAction());
            entry.setEntityType(spec.getEntityType());
            entry.setEntityId(spec.getEntityId());
            entry.setBizNo(spec.getBizNo());
            entry.setFromStatus(spec.getFromStatus());
            entry.setToStatus(spec.getToStatus());
            entry.setResult(result);
            entry.setOperatorName(resolveOperator(spec.getOperator()));
            entry.setRemark(spec.getRemark());
            entry.setCreateTime(LocalDateTime.now());
            transitionLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("[过程迁移] 迁移台账写入失败（scene={}, action={}, entityId={}）：{}",
                    spec.getScene(), spec.getAction(), spec.getEntityId(), e.getMessage());
        }
    }

    private String resolveOperator(String operator) {
        if (StringUtils.hasText(operator)) {
            return operator;
        }
        String username = SecurityUtils.getCurrentUsername();
        return StringUtils.hasText(username) ? username : SYSTEM_OPERATOR;
    }
}
