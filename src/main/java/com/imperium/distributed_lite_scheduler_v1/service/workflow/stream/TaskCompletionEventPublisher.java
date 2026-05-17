package com.imperium.distributed_lite_scheduler_v1.service.workflow.stream;

import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 将任务实例终态事件写入 Redis Stream（Spring Data Redis）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskCompletionEventPublisher {

    private final StringRedisTemplate stringRedisTemplate;

    public void publish(TaskInstance taskInstance) {
        if (taskInstance == null) {
            log.warn("任务实例为空，无法发布事件");
            return;
        }
        try {
            Map<String, String> body = new HashMap<>();
            body.put("taskInstanceId", String.valueOf(taskInstance.getId()));
            if (taskInstance.getWorkflowInstanceId() != null) {
                body.put("workflowInstanceId", String.valueOf(taskInstance.getWorkflowInstanceId()));
            }
            body.put("status", taskInstance.getStatus());
            if (taskInstance.getExitCode() != null) {
                body.put("exitCode", String.valueOf(taskInstance.getExitCode()));
            }
            if (StringUtils.hasText(taskInstance.getErrorMessage())) {
                body.put("errorMessage", taskInstance.getErrorMessage());
            }
            if (taskInstance.getDurationMs() != null) {
                body.put("durationMs", String.valueOf(taskInstance.getDurationMs()));
            }

            RecordId recordId = stringRedisTemplate.opsForStream().add(
                    StreamRecords.string(body).withStreamKey(TaskCompletionStreamConstants.STREAM_KEY));

            log.debug("任务完成事件已发布 stream={} taskInstanceId={} recordId={}",
                    TaskCompletionStreamConstants.STREAM_KEY, taskInstance.getId(), recordId);
        } catch (Exception e) {
            log.error("发布任务完成事件失败 taskInstanceId={}", taskInstance.getId(), e);
        }
    }
}
