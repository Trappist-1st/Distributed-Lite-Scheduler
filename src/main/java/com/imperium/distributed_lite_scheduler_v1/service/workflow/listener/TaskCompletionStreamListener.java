package com.imperium.distributed_lite_scheduler_v1.service.workflow.listener;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskCompletionEvent;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamConstants;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务完成 Redis Stream 监听器：由 {@link com.imperium.distributed_lite_scheduler_v1.config.TaskCompletionRedisStreamConfig}
 * 注册到 {@link org.springframework.data.redis.stream.StreamMessageListenerContainer}，无手写消费循环。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskCompletionStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final TaskCompletionStreamHandler handler;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            TaskCompletionEvent event = toEvent(message.getValue());
            handler.handle(event);
            stringRedisTemplate.opsForStream().acknowledge(
                    TaskCompletionStreamConstants.CONSUMER_GROUP, message);
            log.debug("消息已确认 recordId={} taskInstanceId={}",
                    message.getId(), event.getTaskInstanceId());
        } catch (Exception e) {
            log.error("处理任务完成消息失败 recordId={} body={}", message.getId(), message.getValue(), e);
        }
    }

    private static TaskCompletionEvent toEvent(Map<String, String> data) {
        String taskInstanceIdStr = data.get("taskInstanceId");
        if (taskInstanceIdStr == null || taskInstanceIdStr.isBlank()) {
            throw new IllegalArgumentException("taskInstanceId is required in event data");
        }

        String workflowInstanceIdStr = data.get("workflowInstanceId");
        String exitCodeStr = data.get("exitCode");
        String durationMsStr = data.get("durationMs");

        return TaskCompletionEvent.builder()
                .taskInstanceId(Long.parseLong(taskInstanceIdStr))
                .workflowInstanceId(workflowInstanceIdStr != null && !workflowInstanceIdStr.isBlank()
                        ? Long.parseLong(workflowInstanceIdStr) : null)
                .status(data.get("status"))
                .exitCode(exitCodeStr != null && !exitCodeStr.isBlank() ? Integer.parseInt(exitCodeStr) : null)
                .errorMessage(data.get("errorMessage"))
                .durationMs(durationMsStr != null && !durationMsStr.isBlank() ? Long.parseLong(durationMsStr) : null)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
