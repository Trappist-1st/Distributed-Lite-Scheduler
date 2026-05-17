package com.imperium.distributed_lite_scheduler_v1.service.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 在本地线程池中同步执行单个任务实例并上报结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalTaskRunner {

    private final RunSpecBuilder runSpecBuilder;
    private final TaskTypeExecutorRegistry executorRegistry;
    private final TaskExecutionReporter executionReporter;

    public void run(Long taskInstanceId, Long resourceNodeId) {
        log.info("开始执行任务实例 taskInstanceId={} nodeId={}", taskInstanceId, resourceNodeId);
        try {
            RunSpec runSpec = runSpecBuilder.build(taskInstanceId, resourceNodeId);
            TaskTypeExecutor executor = executorRegistry.resolve(runSpec.getTaskType()).orElse(null);
            if (executor == null) {
                executionReporter.reportConfigurationError(
                        taskInstanceId,
                        "不支持的任务类型: " + runSpec.getTaskType());
                return;
            }

            ExecutionResult result = executor.execute(runSpec);
            executionReporter.report(result, taskInstanceId);
        } catch (Exception e) {
            log.error("任务执行异常 taskInstanceId={}", taskInstanceId, e);
            executionReporter.reportFailure(taskInstanceId, -1, e.getMessage());
        }
    }
}
