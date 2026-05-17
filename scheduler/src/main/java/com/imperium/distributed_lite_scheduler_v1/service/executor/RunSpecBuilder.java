package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * 从 TaskInstance + Task 定义构建 {@link RunSpec}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunSpecBuilder {

    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskMapper taskMapper;
    private final TaskExecutorProperties properties;
    private final ObjectMapper objectMapper;

    public RunSpec build(Long taskInstanceId, Long resourceNodeId) {
        TaskInstance instance = taskInstanceMapper.selectById(taskInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("任务实例不存在: " + taskInstanceId);
        }

        Task task = instance.getTaskId() != null ? taskMapper.selectById(instance.getTaskId()) : null;
        String taskType = task != null && StringUtils.hasText(task.getTaskType())
                ? task.getTaskType().trim()
                : "SHELL";

        String executorConfigJson = StringUtils.hasText(instance.getExecutorConfig())
                ? instance.getExecutorConfig()
                : (task != null ? task.getExecutorConfig() : null);

        Map<String, Object> parameters = parseParameters(instance.getParameters());
        String command = extractCommand(executorConfigJson);
        if (StringUtils.hasText(command)) {
            command = ParameterTemplateResolver.resolve(command, parameters);
        }

        Integer timeoutSeconds = task != null ? task.getTimeoutSeconds() : null;
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = extractTimeoutOverride(executorConfigJson);
        }

        Path workDir = Path.of(properties.getWorkDir(), String.valueOf(taskInstanceId));

        return RunSpec.builder()
                .taskInstanceId(taskInstanceId)
                .taskId(instance.getTaskId())
                .taskType(taskType)
                .command(command)
                .executorConfig(executorConfigJson)
                .workDirectory(workDir)
                .parameters(parameters)
                .timeoutSeconds(timeoutSeconds)
                .resourceNodeId(resourceNodeId)
                .build();
    }

    private Map<String, Object> parseParameters(String parametersJson) {
        if (!StringUtils.hasText(parametersJson)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(parametersJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("任务参数 JSON 解析失败，按空参数处理 json={}", parametersJson, e);
            return Collections.emptyMap();
        }
    }

    private Integer extractTimeoutOverride(String executorConfigJson) {
        if (!StringUtils.hasText(executorConfigJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(executorConfigJson);
            if (root.hasNonNull("timeoutSeconds")) {
                return root.get("timeoutSeconds").asInt();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private String extractCommand(String executorConfigJson) {
        if (!StringUtils.hasText(executorConfigJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(executorConfigJson);
            if (root.hasNonNull("command")) {
                return root.get("command").asText();
            }
            if (root.hasNonNull("script")) {
                return root.get("script").asText();
            }
            return null;
        } catch (Exception e) {
            log.warn("executorConfig 解析失败 json={}", executorConfigJson, e);
            return null;
        }
    }
}
