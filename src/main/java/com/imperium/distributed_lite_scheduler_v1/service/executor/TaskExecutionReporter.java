package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 将执行结果写回任务实例状态机（等价于 Worker 回调内部 API）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutionReporter {

    private static final String TRIGGER_WORKER = "WORKER";

    private final TaskInstanceService taskInstanceService;

    public void report(ExecutionResult result, Long taskInstanceId) {
        if (result.isTimedOut()) {
            reportTimeout(taskInstanceId, result.getErrorMessage());
            return;
        }
        if (result.isSuccess()) {
            reportSuccess(taskInstanceId, result.getExitCode());
        } else {
            reportFailure(taskInstanceId, result.getExitCode(), result.getErrorMessage());
        }
    }

    public void reportSuccess(Long taskInstanceId, int exitCode) {
        transition(
                taskInstanceId,
                TaskInstanceStatus.RUNNING.getCode(),
                TaskInstanceStatus.SUCCESS.getCode(),
                exitCode,
                null,
                "执行成功");
    }

    public void reportFailure(Long taskInstanceId, Integer exitCode, String errorMessage) {
        transition(
                taskInstanceId,
                TaskInstanceStatus.RUNNING.getCode(),
                TaskInstanceStatus.FAILED.getCode(),
                exitCode,
                errorMessage,
                "执行失败");
    }

    public void reportTimeout(Long taskInstanceId, String errorMessage) {
        transition(
                taskInstanceId,
                TaskInstanceStatus.RUNNING.getCode(),
                TaskInstanceStatus.TIMEOUT.getCode(),
                -1,
                errorMessage,
                "执行超时");
    }

    public void reportConfigurationError(Long taskInstanceId, String errorMessage) {
        reportFailure(taskInstanceId, -1, errorMessage);
    }

    private void transition(
            Long taskInstanceId,
            String fromStatus,
            String toStatus,
            Integer exitCode,
            String errorMessage,
            String reason) {
        InternalTaskInstanceStatusTransitionRequest request =
                new InternalTaskInstanceStatusTransitionRequest(
                        fromStatus,
                        toStatus,
                        TRIGGER_WORKER,
                        reason,
                        null,
                        exitCode,
                        errorMessage);

        Result<TaskInstance> result = taskInstanceService.transitionStatus(taskInstanceId, request);
        if (result.isSuccess()) {
            log.info(
                    "任务状态已更新 taskInstanceId={} {} -> {}",
                    taskInstanceId,
                    fromStatus,
                    toStatus);
            return;
        }
        log.warn(
                "任务状态更新未生效 taskInstanceId={} {} -> {} code={} message={}",
                taskInstanceId,
                fromStatus,
                toStatus,
                result.getCode(),
                result.getMessage());
    }
}
