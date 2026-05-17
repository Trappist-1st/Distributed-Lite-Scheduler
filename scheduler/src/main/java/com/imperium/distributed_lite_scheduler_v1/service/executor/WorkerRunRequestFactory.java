package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.model.dto.worker.WorkerRunCallback;
import com.imperium.distributed_lite_scheduler_v1.model.dto.worker.WorkerRunRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将 {@link RunSpec} 转为远程 Worker HTTP 请求体。
 */
@Component
@RequiredArgsConstructor
public class WorkerRunRequestFactory {

    private final TaskExecutorProperties taskExecutorProperties;

    @Value("${internal.api.token:}")
    private String internalApiToken;

    public WorkerRunRequest fromRunSpec(RunSpec runSpec, ResourceNode node) {
        String baseUrl = trimTrailingSlash(taskExecutorProperties.getSchedulerPublicBaseUrl());
        String statusUrl =
                baseUrl + "/api/internal/task-instances/" + runSpec.getTaskInstanceId() + "/status";

        WorkerRunCallback callback = WorkerRunCallback.builder()
                .statusTransitionUrl(statusUrl)
                .internalToken(internalApiToken)
                .build();

        return WorkerRunRequest.builder()
                .taskInstanceId(runSpec.getTaskInstanceId())
                .resourceNodeId(node != null ? node.getId() : runSpec.getResourceNodeId())
                .taskType(runSpec.getTaskType())
                .command(runSpec.getCommand())
                .timeoutSeconds(runSpec.getTimeoutSeconds())
                .executorConfig(runSpec.getExecutorConfig())
                .parameters(runSpec.getParameters())
                .callback(callback)
                .build();
    }

    private static String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return "http://localhost:8080";
        }
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
