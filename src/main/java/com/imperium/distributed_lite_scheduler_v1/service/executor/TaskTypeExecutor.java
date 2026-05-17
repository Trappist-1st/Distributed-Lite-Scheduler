package com.imperium.distributed_lite_scheduler_v1.service.executor;

/**
 * 按任务类型执行的具体执行器插件。
 */
public interface TaskTypeExecutor {

    boolean supports(String taskType);

    ExecutionResult execute(RunSpec runSpec) throws Exception;
}
