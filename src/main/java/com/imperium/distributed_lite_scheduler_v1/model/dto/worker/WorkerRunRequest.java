package com.imperium.distributed_lite_scheduler_v1.model.dto.worker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 调度器下发至远程 Worker 的运行请求（命令已由调度端解析与参数渲染）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerRunRequest {

    private Long taskInstanceId;

    private Long resourceNodeId;

    private String taskType;

    private String command;

    private Integer timeoutSeconds;

    private String executorConfig;

    private Map<String, Object> parameters;

    private WorkerRunCallback callback;
}
