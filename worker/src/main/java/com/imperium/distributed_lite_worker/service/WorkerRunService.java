package com.imperium.distributed_lite_worker.service;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.dto.WorkerRunRequest;
import com.imperium.distributed_lite_worker.executor.ExecutionResult;
import com.imperium.distributed_lite_worker.executor.LocalRunSpec;
import com.imperium.distributed_lite_worker.executor.WorkerTaskTypeExecutor;
import com.imperium.distributed_lite_worker.executor.WorkerTaskTypeExecutorRegistry;
import com.imperium.distributed_lite_worker.runtime.RunningTaskRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class WorkerRunService {

    private final WorkerProperties properties;
    private final WorkerTaskTypeExecutorRegistry executorRegistry;
    private final SchedulerCallbackClient schedulerCallbackClient;
    private final RunningTaskRegistry runningTaskRegistry;
    private final Executor workerTaskExecutor;

    public WorkerRunService(
            WorkerProperties properties,
            WorkerTaskTypeExecutorRegistry executorRegistry,
            SchedulerCallbackClient schedulerCallbackClient,
            RunningTaskRegistry runningTaskRegistry,
            @Qualifier("workerTaskExecutor") Executor workerTaskExecutor) {
        this.properties = properties;
        this.executorRegistry = executorRegistry;
        this.schedulerCallbackClient = schedulerCallbackClient;
        this.runningTaskRegistry = runningTaskRegistry;
        this.workerTaskExecutor = workerTaskExecutor;
    }

    public void submitAsync(WorkerRunRequest request) {
        try {
            workerTaskExecutor.execute(() -> executeSync(request));
        } catch (RejectedExecutionException e) {
            log.warn("Worker 线程池已满 taskInstanceId={}", request.getTaskInstanceId(), e);
            schedulerCallbackClient.report(
                    request.getCallback(),
                    ExecutionResult.configurationError("Worker 线程池已满"));
        }
    }

    public boolean cancel(Long taskInstanceId) {
        return runningTaskRegistry.cancel(taskInstanceId);
    }

    private void executeSync(WorkerRunRequest request) {
        log.info("Worker 执行任务 taskInstanceId={} type={}", request.getTaskInstanceId(), request.getTaskType());
        try {
            WorkerTaskTypeExecutor executor = executorRegistry.resolve(request.getTaskType()).orElse(null);
            if (executor == null) {
                schedulerCallbackClient.report(
                        request.getCallback(),
                        ExecutionResult.configurationError("不支持的任务类型: " + request.getTaskType()));
                return;
            }

            Path workDir =
                    Path.of(properties.getExecutor().getWorkDir(), String.valueOf(request.getTaskInstanceId()));

            LocalRunSpec spec = LocalRunSpec.builder()
                    .taskInstanceId(request.getTaskInstanceId())
                    .taskType(request.getTaskType())
                    .command(request.getCommand())
                    .executorConfig(request.getExecutorConfig())
                    .workDirectory(workDir)
                    .timeoutSeconds(request.getTimeoutSeconds())
                    .parameters(request.getParameters())
                    .build();

            ExecutionResult result = executor.execute(spec);
            schedulerCallbackClient.report(request.getCallback(), result);
        } catch (Exception e) {
            log.error("Worker 任务执行异常 taskInstanceId={}", request.getTaskInstanceId(), e);
            schedulerCallbackClient.report(
                    request.getCallback(), ExecutionResult.failure(-1, null, null, e.getMessage()));
        }
    }
}
