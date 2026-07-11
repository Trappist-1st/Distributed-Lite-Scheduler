package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务自动重试服务。
 *
 * <h3>重试触发场景</h3>
 * <ol>
 *   <li>任务执行失败（{@code status=FAILED}）且 {@code retry_count < task.retry_times}</li>
 *   <li>任务超时（{@code status=TIMEOUT}）且 task 配置允许超时重试</li>
 *   <li>节点宕机恢复（{@link NodeHeartbeatWatchdog} 强制 FAILED 后调用本服务）</li>
 * </ol>
 *
 * <h3>重试语义</h3>
 * <p>每次重试创建新的 {@code task_instance}（新 ID），{@code instance_code} 不变，
 * {@code retry_count = old + 1}，状态重置为 PENDING，重新进入调度队列。
 * 调用方（任务执行完成 handler）负责判断是否满足重试条件，本服务只负责构造和提交。
 *
 * <h3>幂等性</h3>
 * <p>相同 {@code instance_code + retry_count} 组合在 DB 中应该唯一，
 * 保证对账 Worker 重复调用时不会产生多余的重试实例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRetryService {

    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskMapper taskMapper;
    private final TaskSubmitService taskSubmitService;

    /**
     * 评估失败任务是否应该重试，若是则创建新实例重新入队。
     *
     * @param failedTaskInstance 刚变为终态（FAILED/TIMEOUT）的任务实例
     * @param failReason         失败原因（写入新实例的 errorMessage）
     * @return true 表示已触发重试；false 表示达到重试上限或 task 不允许重试
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean retryIfNeeded(TaskInstance failedTaskInstance, String failReason) {
        if (failedTaskInstance == null) return false;

        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, failedTaskInstance.getTaskId())
                .eq(Task::getStatus, 1));

        if (task == null) {
            log.warn("任务定义不存在或已禁用，跳过重试 taskId={}", failedTaskInstance.getTaskId());
            return false;
        }

        int maxRetries = task.getRetryTimes() == null ? 0 : task.getRetryTimes();
        int currentRetry = failedTaskInstance.getRetryCount() == null ? 0 : failedTaskInstance.getRetryCount();

        if (currentRetry >= maxRetries) {
            log.info("已达到最大重试次数，不再重试 taskInstanceId={} retryCount={} maxRetries={}",
                    failedTaskInstance.getId(), currentRetry, maxRetries);
            return false;
        }

        // 如果配置了重试间隔，等待一段时间（通过 scheduledTime 实现延迟调度）
        int retryIntervalSeconds = task.getRetryInterval() == null ? 0 : task.getRetryInterval();

        return submitRetryInstance(failedTaskInstance, task, currentRetry + 1, retryIntervalSeconds, failReason);
    }

    private boolean submitRetryInstance(TaskInstance original, Task task, int newRetryCount,
                                        int delaySeconds, String retryReason) {
        TaskSubmitRequest retryRequest = new TaskSubmitRequest();
        retryRequest.setTaskId(original.getTaskId());
        retryRequest.setTenantId(original.getTenantId());
        retryRequest.setSubmitUserId(original.getSubmitUserId());
        retryRequest.setWorkflowInstanceId(original.getWorkflowInstanceId());
        retryRequest.setTriggerType("RETRY");
        retryRequest.setPriority(original.getPriority());
        retryRequest.setParameters(parseParameters(original.getParameters()));
        retryRequest.setResourceRequirement(original.getResourceRequirement());
        retryRequest.setExecutorConfig(original.getExecutorConfig());
        retryRequest.setRetryCount(newRetryCount);

        // 延迟调度：scheduledTime = now + retryInterval
        if (delaySeconds > 0) {
            retryRequest.setScheduledTime(LocalDateTime.now().plusSeconds(delaySeconds));
        }

        Result<?> result = taskSubmitService.submitTask(retryRequest);
        if (result.isSuccess()) {
            log.info("触发任务重试 originalTaskInstanceId={} newRetryCount={}/{} reason={}",
                    original.getId(), newRetryCount, task.getRetryTimes(), retryReason);
            return true;
        } else {
            log.error("触发重试失败 originalTaskInstanceId={} reason={}", original.getId(), result.getMessage());
            return false;
        }
    }

    private java.util.Map<String, Object> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(parametersJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }
}
