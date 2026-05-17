package com.imperium.distributed_lite_worker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class WorkerRunRequest {

    @NotNull
    private Long taskInstanceId;

    private Long resourceNodeId;

    @NotBlank
    private String taskType;

    @NotBlank
    private String command;

    private Integer timeoutSeconds;

    private String executorConfig;

    private Map<String, Object> parameters;

    @NotNull
    @Valid
    private WorkerRunCallback callback;
}
