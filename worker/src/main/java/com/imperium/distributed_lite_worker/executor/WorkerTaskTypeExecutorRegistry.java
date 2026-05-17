package com.imperium.distributed_lite_worker.executor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WorkerTaskTypeExecutorRegistry {

    private final List<WorkerTaskTypeExecutor> executors;

    public Optional<WorkerTaskTypeExecutor> resolve(String taskType) {
        return executors.stream().filter(e -> e.supports(taskType)).findFirst();
    }
}
