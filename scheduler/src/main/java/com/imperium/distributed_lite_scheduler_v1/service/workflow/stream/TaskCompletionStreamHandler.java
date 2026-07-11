package com.imperium.distributed_lite_scheduler_v1.service.workflow.stream;

import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskCompletionEvent;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.impl.WorkflowLayerDispatchFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务完成 Stream 事件的业务处理：更新工作流任务状态并驱动 DAG 下一层。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskCompletionStreamHandler {

    private final WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowLayerDispatchFacade workflowLayerDispatchFacade;

    public void handle(TaskCompletionEvent event) {
        log.info(
                "处理任务完成事件 taskInstanceId={} status={} workflowInstanceId={}",
                event.getTaskInstanceId(),
                event.getStatus(),
                event.getWorkflowInstanceId());

        if (event.getWorkflowInstanceId() == null) {
            log.debug("非工作流任务，跳过 taskInstanceId={}", event.getTaskInstanceId());
            return;
        }

        List<WorkflowTaskInstance> workflowTaskInstances =
                workflowTaskInstanceMapper.selectByTaskInstanceId(event.getTaskInstanceId());

        if (workflowTaskInstances.isEmpty()) {
            log.warn("未找到关联的 WorkflowTaskInstance taskInstanceId={}", event.getTaskInstanceId());
            return;
        }

        for (WorkflowTaskInstance wtInstance : workflowTaskInstances) {
            // 幂等保护：workflow_task_instance 已为终态说明此事件已被成功处理过，直接跳过
            // 触发场景：consumer group reset、XCLAIM 重投、手动 replay
            try {
                TaskInstanceStatus currentStatus = TaskInstanceStatus.fromCode(wtInstance.getStatus());
                if (currentStatus.isTerminal()) {
                    log.info("幂等跳过：workflow_task_instance 已为终态，忽略重复事件 "
                                    + "workflowTaskInstanceId={} currentStatus={} eventTaskInstanceId={}",
                            wtInstance.getId(), wtInstance.getStatus(), event.getTaskInstanceId());
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
                // 未知状态，继续正常处理
            }

            if (TaskInstanceStatus.SUCCESS.matches(event.getStatus())) {
                handleTaskSuccess(wtInstance, event);
            } else if (TaskInstanceStatus.FAILED.matches(event.getStatus())
                    || TaskInstanceStatus.TIMEOUT.matches(event.getStatus())
                    || TaskInstanceStatus.CANCELLED.matches(event.getStatus())) {
                handleTaskFailure(wtInstance, event);
            }
        }
    }

    private void handleTaskSuccess(WorkflowTaskInstance wtInstance, TaskCompletionEvent event) {
        log.info(
                "任务执行成功 workflowTaskInstanceId={} taskInstanceId={}",
                wtInstance.getId(),
                event.getTaskInstanceId());

        wtInstance.setStatus(TaskInstanceStatus.SUCCESS.getCode());
        wtInstance.setEndTime(LocalDateTime.now());
        wtInstance.setExitCode(event.getExitCode());
        wtInstance.setDurationSeconds(
                event.getDurationMs() != null ? (int) (event.getDurationMs() / 1000) : null);

        workflowTaskInstanceMapper.updateById(wtInstance);
        workflowInstanceMapper.incrementCompletedTasks(wtInstance.getWorkflowInstanceId());
        workflowLayerDispatchFacade.onWorkflowTaskTerminated(
                wtInstance.getWorkflowInstanceId(), wtInstance.getLayerIndex());
    }

    private void handleTaskFailure(WorkflowTaskInstance wtInstance, TaskCompletionEvent event) {
        log.warn(
                "任务执行失败 workflowTaskInstanceId={} taskInstanceId={} error={}",
                wtInstance.getId(),
                event.getTaskInstanceId(),
                event.getErrorMessage());

        wtInstance.setStatus(TaskInstanceStatus.FAILED.getCode());
        wtInstance.setEndTime(LocalDateTime.now());
        wtInstance.setExitCode(event.getExitCode());
        wtInstance.setErrorMessage(event.getErrorMessage());
        wtInstance.setDurationSeconds(
                event.getDurationMs() != null ? (int) (event.getDurationMs() / 1000) : null);

        workflowTaskInstanceMapper.updateById(wtInstance);
        workflowInstanceMapper.incrementFailedTasks(wtInstance.getWorkflowInstanceId());
        workflowLayerDispatchFacade.onWorkflowTaskTerminated(
                wtInstance.getWorkflowInstanceId(), wtInstance.getLayerIndex());
    }
}
