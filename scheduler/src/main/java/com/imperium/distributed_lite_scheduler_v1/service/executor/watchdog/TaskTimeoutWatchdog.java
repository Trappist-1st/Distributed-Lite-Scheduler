package com.imperium.distributed_lite_scheduler_v1.service.executor.watchdog;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskExecutionCancelService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.SchedulerLeaderElection;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.TaskRetryService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 扫描 RUNNING 超时任务，终止执行并流转为 TIMEOUT。
 * <p>仅 Leader 节点执行，避免多实例重复扫描与重复 cancel。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutWatchdog {

    private final TaskExecutorProperties properties;
    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskInstanceService taskInstanceService;
    private final TaskExecutionCancelService taskExecutionCancelService;
    private final TaskRetryService taskRetryService;
    private final SchedulerLeaderElection schedulerLeaderElection;

    @Scheduled(fixedDelayString = "${task.executor.timeout-scan-interval-ms:30000}")
    public void scanTimedOutTasks() {
        if (!properties.isTimeoutWatchEnabled()) {
            return;
        }
        boolean executed = schedulerLeaderElection.executeIfLeader(() -> {
            List<TaskInstance> timedOut =
                    taskInstanceMapper.selectTimedOutRunningTasks(properties.getTimeoutScanBatchSize());
            if (timedOut.isEmpty()) {
                return;
            }
            log.info("检测到超时 RUNNING 任务 count={}", timedOut.size());
            for (TaskInstance instance : timedOut) {
                handleTimedOut(instance);
            }
        });
        if (!executed) {
            log.debug("非 Leader 节点，跳过超时任务扫描");
        }
    }

    private void handleTimedOut(TaskInstance instance) {
        Long taskInstanceId = instance.getId();
        taskExecutionCancelService.cancelRunningTask(taskInstanceId, instance.getResourceNodeId());

        InternalTaskInstanceStatusTransitionRequest request =
                new InternalTaskInstanceStatusTransitionRequest(
                        TaskInstanceStatus.RUNNING.getCode(),
                        TaskInstanceStatus.TIMEOUT.getCode(),
                        "SYSTEM",
                        "调度器超时守护",
                        null,
                        -1,
                        "任务执行超过 timeoutSeconds 限制");

        Result<TaskInstance> result = taskInstanceService.transitionStatus(taskInstanceId, request);
        if (result.isSuccess()) {
            log.info("任务已标记为 TIMEOUT taskInstanceId={}", taskInstanceId);
            try {
                taskRetryService.retryIfNeeded(instance, "task timeout exceeded");
            } catch (Exception e) {
                log.error("超时后触发重试失败 taskInstanceId={}", taskInstanceId, e);
            }
        } else {
            log.warn("任务超时状态更新失败 taskInstanceId={} message={}", taskInstanceId, result.getMessage());
        }
    }
}
