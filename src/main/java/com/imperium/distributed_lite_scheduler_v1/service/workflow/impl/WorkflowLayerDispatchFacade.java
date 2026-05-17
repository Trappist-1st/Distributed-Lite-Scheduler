package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.condition.WorkflowConditionalLayerGate;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 单层提交与收口推进（P4-4）：Stream 回调与 {@link WorkflowExecutorImpl} 共用，避免分叉行为。
 *
 * <p>在「整层已为终态但无 RUNNING→Stream 回调」的场景（典型为条件整层 SKIPPED），
 * {@link #dispatchLayer(Long, int)} 末尾会递归推进直至出现待调度任务或工作流完结。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowLayerDispatchFacade {

    private final WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final TaskSubmitService taskSubmitService;
    private final ObjectMapper objectMapper;
    private final WorkflowConditionalLayerGate conditionalLayerGate;

    /**
     * 某 WorkflowTaskInstance 刚进入终态后调用：在当前层<strong>整体</strong>终态时再打开下一层。
     */
    public void onWorkflowTaskTerminated(Long workflowInstanceId, int taskCompletedLayerIndex) {
        try {
            WorkflowInstance flow = workflowInstanceMapper.selectById(workflowInstanceId);
            if (flow != null) {
                WorkflowInstanceStatus wfStatus = parseWorkflowInstanceStatus(flow.getStatus());
                if (wfStatus == WorkflowInstanceStatus.PAUSED
                        || wfStatus == WorkflowInstanceStatus.CANCELLED) {
                    log.debug(
                            "工作流实例为 {}，不再驱动下一层 instanceId={}",
                            wfStatus.name(),
                            workflowInstanceId);
                    return;
                }
            }

            List<WorkflowTaskInstance> currentLayerTasks =
                    workflowTaskInstanceMapper.selectByInstanceIdAndLayer(
                            workflowInstanceId, taskCompletedLayerIndex);

            boolean allTerminal =
                    currentLayerTasks.stream().allMatch(WorkflowLayerDispatchFacade::isWorkflowTaskTerminal);

            if (!allTerminal) {
                log.debug(
                        "第{}层任务尚未全部完成 instanceId={}",
                        taskCompletedLayerIndex,
                        workflowInstanceId);
                return;
            }

            log.info("第{}层任务全部完成 instanceId={}", taskCompletedLayerIndex, workflowInstanceId);
            proceedAfterLayerFinished(workflowInstanceId, taskCompletedLayerIndex);

        } catch (Exception e) {
            log.error(
                    "工作流层级推进失败 instanceId={} layerIndex={}",
                    workflowInstanceId,
                    taskCompletedLayerIndex,
                    e);
        }
    }

    /**
     * 投递某层仍为 PENDING 的 WorkflowTaskInstance 到调度器；含 P4-4 门前条件门控，
     * 并在整层已终态时再自动推进后续层。
     */
    public void dispatchLayer(Long workflowInstanceId, int layerIndex, Long submitPrincipalId)
            throws Exception {
        conditionalLayerGate.applyBeforeLayerSubmit(workflowInstanceId, layerIndex);

        log.info("提交第{}层任务 instanceId={}", layerIndex, workflowInstanceId);

        List<WorkflowTaskInstance> layerTasks =
                workflowTaskInstanceMapper.selectByInstanceIdAndLayer(workflowInstanceId, layerIndex);
        if (layerTasks.isEmpty()) {
            log.warn("第{}层没有任务 instanceId={}，视作已结束并尝试推进", layerIndex, workflowInstanceId);
            proceedAfterLayerFinished(workflowInstanceId, layerIndex);
            return;
        }

        WorkflowInstance instance =
                workflowInstanceMapper.selectById(workflowInstanceId);
        Long tenantId =
                instance != null && instance.getTenantId() != null
                        ? instance.getTenantId()
                        : instance != null ? instance.getTriggerUserId() : null;

        Long effectiveSubmitUser =
                submitPrincipalId != null
                        ? submitPrincipalId
                        : instance != null
                                ? (instance.getTriggerUserId() != null
                                        ? instance.getTriggerUserId()
                                        : instance.getTenantId())
                                : tenantId;

        for (WorkflowTaskInstance wtInstance : layerTasks) {
            try {
                if (!TaskInstanceStatus.PENDING.matches(wtInstance.getStatus())) {
                    continue;
                }
                TaskInstance taskInstance = wtInstance.toTaskInstance(tenantId, effectiveSubmitUser);
                Result<TaskSubmitResponse> result =
                        taskSubmitService.submitTask(buildTaskSubmitRequest(taskInstance));
                if (!result.isSuccess()) {
                    throw new RuntimeException("任务提交失败: " + result.getMessage());
                }
                TaskSubmitResponse submitResponse = result.getData();
                if (submitResponse != null && submitResponse.getTaskInstanceId() != null) {
                    wtInstance.setTaskInstanceId(submitResponse.getTaskInstanceId());
                    workflowTaskInstanceMapper.updateById(wtInstance);
                }
                log.info(
                        "任务已提交到调度器 taskName={} workflowTaskInstanceId={} taskInstanceId={}",
                        wtInstance.getTaskName(),
                        wtInstance.getId(),
                        submitResponse != null ? submitResponse.getTaskInstanceId() : null);
            } catch (Exception e) {
                log.error(
                        "提交任务失败 taskName={} workflowTaskInstanceId={}",
                        wtInstance.getTaskName(),
                        wtInstance.getId(),
                        e);
                wtInstance.setStatus(TaskInstanceStatus.FAILED.getCode());
                wtInstance.setErrorMessage("提交任务失败: " + e.getMessage());
                wtInstance.setEndTime(LocalDateTime.now());
                workflowTaskInstanceMapper.updateById(wtInstance);
                workflowInstanceMapper.incrementFailedTasks(workflowInstanceId);
            }
        }

        reconcileSilentLayerTermination(workflowInstanceId, layerIndex);
    }

    private void reconcileSilentLayerTermination(Long workflowInstanceId, int layerIndex)
            throws Exception {
        List<WorkflowTaskInstance> layerTasks =
                workflowTaskInstanceMapper.selectByInstanceIdAndLayer(workflowInstanceId, layerIndex);
        if (layerTasks.isEmpty()) {
            return;
        }
        boolean allTerminal = layerTasks.stream().allMatch(WorkflowLayerDispatchFacade::isWorkflowTaskTerminal);
        if (allTerminal) {
            proceedAfterLayerFinished(workflowInstanceId, layerIndex);
        }
    }

    private void proceedAfterLayerFinished(Long workflowInstanceId, int completedLayerIndex)
            throws Exception {
        WorkflowInstance instance = workflowInstanceMapper.selectById(workflowInstanceId);
        if (instance == null) {
            log.warn("工作流实例不存在，跳过后续推进 instanceId={}", workflowInstanceId);
            return;
        }
        WorkflowExecutionPlan plan =
                objectMapper.readValue(instance.getExecutionPlan(), WorkflowExecutionPlan.class);

        int nextLayer = completedLayerIndex + 1;
        if (nextLayer < plan.getLayers().size()) {
            Long submitPrincipal =
                    instance.getTriggerUserId() != null
                            ? instance.getTriggerUserId()
                            : instance.getTenantId();
            log.info("提交下一层任务 instanceId={} nextLayer={}", workflowInstanceId, nextLayer);
            dispatchLayer(workflowInstanceId, nextLayer, submitPrincipal);
        } else {
            log.info("所有层任务已完成，标记工作流完成 instanceId={}", workflowInstanceId);
            completeWorkflowInstance(instance);
        }
    }

    private void completeWorkflowInstance(WorkflowInstance instance) {
        instance.setEndTime(LocalDateTime.now());
        if (instance.getStartTime() != null) {
            long ms = Duration.between(instance.getStartTime(), instance.getEndTime()).toMillis();
            instance.setDurationMs(ms);
        }
        if (instance.getFailedTasks() != null && instance.getFailedTasks() > 0) {
            instance.setStatus(WorkflowInstanceStatus.PARTIAL_SUCCESS.getCode());
        } else {
            instance.setStatus(WorkflowInstanceStatus.SUCCESS.getCode());
        }
        workflowInstanceMapper.updateById(instance);
        log.info(
                "工作流实例执行完成 instanceId={} status={} durationMs={}",
                instance.getId(),
                instance.getStatus(),
                instance.getDurationMs());
    }

    private static boolean isWorkflowTaskTerminal(WorkflowTaskInstance task) {
        try {
            return TaskInstanceStatus.fromCode(task.getStatus()).isTerminal();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private TaskSubmitRequest buildTaskSubmitRequest(TaskInstance taskInstance) {
        return TaskSubmitRequest.builder()
                .taskId(taskInstance.getTaskId())
                .priority(taskInstance.getPriority())
                .tenantId(taskInstance.getTenantId())
                .submitUserId(taskInstance.getSubmitUserId())
                .workflowInstanceId(taskInstance.getWorkflowInstanceId())
                .executorConfig(taskInstance.getExecutorConfig())
                .resourceRequirement(taskInstance.getResourceRequirement())
                .parameters(parseParametersMap(taskInstance.getParameters()))
                .build();
    }

    private java.util.Map<String, Object> parseParametersMap(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readValue(parametersJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("工作流任务参数解析失败 json={}", parametersJson, e);
            return null;
        }
    }

    /**
     * 兼容库中枚举名（如 RUNNING）、code（如 running）或历史混合大小写的状态字段。
     */
    private static WorkflowInstanceStatus parseWorkflowInstanceStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("工作流实例状态为空");
        }
        String s = raw.trim();
        for (WorkflowInstanceStatus st : WorkflowInstanceStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getCode().equalsIgnoreCase(s)) {
                return st;
            }
        }
        throw new IllegalArgumentException("Unsupported workflow instance status: " + raw);
    }
}
