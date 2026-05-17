package com.imperium.distributed_lite_scheduler_v1.service.executor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 按 taskType 路由到具体 {@link TaskTypeExecutor}。
 */
@Component
@RequiredArgsConstructor
public class TaskTypeExecutorRegistry {

    private final List<TaskTypeExecutor> executors;

    public Optional<TaskTypeExecutor> resolve(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return Optional.empty();
        }
        return executors.stream()
                .filter(executor -> executor.supports(taskType))
                .findFirst();
    }
}
