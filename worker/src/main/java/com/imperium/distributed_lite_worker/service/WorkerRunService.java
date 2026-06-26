package com.imperium.distributed_lite_worker.service;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.dto.WorkerRunRequest;
import com.imperium.distributed_lite_worker.executor.ExecutionResult;
import com.imperium.distributed_lite_worker.executor.LocalRunSpec;
import com.imperium.distributed_lite_worker.executor.WorkerTaskTypeExecutor;
import com.imperium.distributed_lite_worker.executor.WorkerTaskTypeExecutorRegistry;
import com.imperium.distributed_lite_worker.runtime.RunningTaskRegistry;
import com.imperium.distributed_lite_worker.runtime.WorkerRunAcceptanceRegistry;
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
    private final WorkerRunAcceptanceRegistry acceptanceRegistry;
    private final Executor workerTaskExecutor;

    public WorkerRunService(
            WorkerProperties properties,
            WorkerTaskTypeExecutorRegistry executorRegistry,
            SchedulerCallbackClient schedulerCallbackClient,
            RunningTaskRegistry runningTaskRegistry,
            WorkerRunAcceptanceRegistry acceptanceRegistry,
            @Qualifier("workerTaskExecutor") Executor workerTaskExecutor) {
        this.properties = properties;
        this.executorRegistry = executorRegistry;
        this.schedulerCallbackClient = schedulerCallbackClient;
        this.runningTaskRegistry = runningTaskRegistry;
        this.acceptanceRegistry = acceptanceRegistry;
        this.workerTaskExecutor = workerTaskExecutor;
    }

    /**
     * 幂等接受任务：同一 {@code taskInstanceId} 在飞行中重复 POST 不再提交线程池。
     * HTTP 仍返回 202，便于 Scheduler 侧将重复下发视为成功接受（WorkerHttpClient 只认 202）。
     */
    public WorkerSubmitOutcome submitAsync(WorkerRunRequest request) {
        long taskInstanceId = request.getTaskInstanceId();
        if (!acceptanceRegistry.tryAccept(taskInstanceId)) {
            log.info("忽略重复下发 taskInstanceId={}（已在执行或排队）", taskInstanceId);
            return WorkerSubmitOutcome.ALREADY_ACCEPTED;
        }

        try {
            workerTaskExecutor.execute(() -> {
                try {
                    executeSync(request);
                } finally {
                    acceptanceRegistry.release(taskInstanceId);
                }
            });
            return WorkerSubmitOutcome.ACCEPTED;
        } catch (RejectedExecutionException e) {
            acceptanceRegistry.release(taskInstanceId);
            log.warn("Worker 线程池已满 taskInstanceId={}", taskInstanceId, e);
            schedulerCallbackClient.report(
                    request.getCallback(),
                    ExecutionResult.configurationError("Worker 线程池已满"));
            return WorkerSubmitOutcome.REJECTED_POOL_FULL;
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
