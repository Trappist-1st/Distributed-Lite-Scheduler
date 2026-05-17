package com.imperium.distributed_lite_scheduler_v1.service.workflow.condition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDependency;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.ConditionContext;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分层投递前的条件门控（P4-4）。
 *
 * <p>在下游层首次提交前：若某节点的<strong>任意一条携带 condition 的入边</strong>求值为假，
 * 则将节点标记为 SKIPPED（AND 语义：所有有条件入边必须为真）。无端条件或空白 condition 不入 SpEL，
 * 仅要求前驱已全部终态（由分层推进约束保证）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowConditionalLayerGate {

    private final WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final ConditionEvaluator conditionEvaluator;
    private final ConditionContextBuilder conditionContextBuilder;
    private final ObjectMapper objectMapper;

    /**
     * 在向外层提交调度前：将该层仍为 PENDING 且条件不成立的节点写入 SKIPPED，避免层内长期 PENDING 死锁。
     */
    public void applyBeforeLayerSubmit(Long workflowInstanceId, int layerIndex) {
        try {
            applyBeforeLayerSubmitInternal(workflowInstanceId, layerIndex);
        } catch (Exception e) {
            log.error(
                    "P4-4 条件门控失败 instanceId={} layerIndex={}（工作流仍可继续但不保证跳过分支）",
                    workflowInstanceId,
                    layerIndex,
                    e);
        }
    }

    private void applyBeforeLayerSubmitInternal(Long workflowInstanceId, int layerIndex)
            throws Exception {
        WorkflowInstance inst = workflowInstanceMapper.selectById(workflowInstanceId);
        if (inst == null) {
            return;
        }

        WorkflowExecutionPlan plan =
                objectMapper.readValue(inst.getExecutionPlan(), WorkflowExecutionPlan.class);
        List<WorkflowDependency> deps = plan.getDependencies();
        if (deps == null || deps.isEmpty()) {
            return;
        }

        List<WorkflowTaskInstance> layerTasks =
                workflowTaskInstanceMapper.selectByInstanceIdAndLayer(workflowInstanceId, layerIndex);
        boolean anyPending =
                layerTasks.stream()
                        .anyMatch(
                                w ->
                                        TaskInstanceStatus.PENDING.matches(w.getStatus()));
        if (!anyPending) {
            return;
        }

        Map<String, WorkflowTaskInstance> byName =
                workflowTaskInstanceMapper.selectByInstanceId(workflowInstanceId).stream()
                        .collect(Collectors.toMap(WorkflowTaskInstance::getTaskName, x -> x, (a, b) -> a));

        ConditionContext ctx = conditionContextBuilder.buildContext(workflowInstanceId);

        for (WorkflowTaskInstance wt : layerTasks) {
            if (!TaskInstanceStatus.PENDING.matches(wt.getStatus())) {
                continue;
            }
            if (shouldSkipNode(wt.getTaskName(), deps, ctx, byName)) {
                markSkippedConditionNotMet(wt);
            }
        }
    }

    private boolean shouldSkipNode(
            String taskName,
            List<WorkflowDependency> deps,
            ConditionContext ctx,
            Map<String, WorkflowTaskInstance> byName) {
        List<WorkflowDependency> inbound =
                deps.stream()
                        .filter(d -> taskName.equals(d.getTo()))
                        .collect(Collectors.toList());
        if (inbound.isEmpty()) {
            return false;
        }

        for (WorkflowDependency d : inbound) {
            WorkflowTaskInstance pred = byName.get(d.getFrom());
            if (pred == null) {
                log.warn(
                        "P4-4 跳过节点：上游任务实例缺失 instanceId downstream={}",
                        taskName);
                return true;
            }
            TaskInstanceStatus ps = resolveStatus(pred.getStatus());
            if (ps == null || !ps.isTerminal()) {
                log.warn(
                        "P4-4 跳过节点：上游未终态 from={} status={}",
                        pred.getTaskName(),
                        pred.getStatus());
                return true;
            }
            if (!StringUtils.hasText(d.getCondition())) {
                continue;
            }
            if (!conditionEvaluator.evaluate(d.getCondition(), ctx)) {
                log.info(
                        "P4-4 边上条件未满足，跳过下游 task={} upstream={}",
                        taskName,
                        d.getFrom());
                return true;
            }
        }
        return false;
    }

    private static TaskInstanceStatus resolveStatus(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return TaskInstanceStatus.fromCode(code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void markSkippedConditionNotMet(WorkflowTaskInstance wt) {
        wt.setStatus(TaskInstanceStatus.SKIPPED.getCode());
        wt.setEndTime(LocalDateTime.now());
        wt.setErrorMessage(
                "Skipped by conditional edge (P4-4)");
        workflowTaskInstanceMapper.updateById(wt);
    }
}
