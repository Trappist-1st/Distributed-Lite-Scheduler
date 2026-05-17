package com.imperium.distributed_lite_worker.executor;

public interface WorkerTaskTypeExecutor {

    boolean supports(String taskType);

    ExecutionResult execute(LocalRunSpec runSpec) throws Exception;
}
