package com.imperium.distributed_lite_scheduler_v1.service.executor;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.Map;

/**
 * 调度器下发给执行器的运行规格（Step1 进程内）。
 */
@Value
@Builder
public class RunSpec {

    Long taskInstanceId;

    Long taskId;

    String taskType;

    String command;

    /** 原始 executorConfig JSON，供 PYTHON/DOCKER 等插件解析 */
    String executorConfig;

    Path workDirectory;

    Map<String, Object> parameters;

    Integer timeoutSeconds;

    Long resourceNodeId;
}
