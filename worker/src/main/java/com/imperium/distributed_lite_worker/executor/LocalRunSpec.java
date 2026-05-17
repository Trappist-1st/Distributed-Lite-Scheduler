package com.imperium.distributed_lite_worker.executor;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.Map;

@Value
@Builder
public class LocalRunSpec {

    Long taskInstanceId;

    String taskType;

    String command;

    String executorConfig;

    Path workDirectory;

    Integer timeoutSeconds;

    Map<String, Object> parameters;
}
