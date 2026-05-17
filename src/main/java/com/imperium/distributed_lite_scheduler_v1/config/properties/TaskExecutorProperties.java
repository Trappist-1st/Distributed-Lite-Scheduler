package com.imperium.distributed_lite_scheduler_v1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务执行器配置（in-process / remote）。
 */
@Data
@ConfigurationProperties(prefix = "task.executor")
public class TaskExecutorProperties {

    /**
     * 是否启用执行器；关闭时调度器 submitToExecutor 将返回 false。
     */
    private boolean enabled = true;

    /**
     * 执行模式：in-process（本机）或 remote（HTTP 下发 Worker）。
     */
    private String mode = "in-process";

    /**
     * 调度中心对外根 URL，供 Worker 回调 /api/internal/task-instances/{id}/status。
     */
    private String schedulerPublicBaseUrl = "http://localhost:8080";

    /**
     * 调用 Worker 的下发路径（相对 Worker 根地址）。
     */
    private String workerDispatchPath = "/api/worker/runs";

    private int workerConnectTimeoutMs = 5_000;

    private int workerReadTimeoutMs = 15_000;

    /**
     * 调度器调用 Worker 时携带的令牌（与 Worker worker.api.token 一致）。
     */
    private String workerApiToken = "";

    private int corePoolSize = 4;

    private int maxPoolSize = 16;

    private int queueCapacity = 200;

    /**
     * 每个任务实例的工作目录根路径（配置为空时回退到系统临时目录）。
     */
    private String workDir = "";

    public String getWorkDir() {
        if (workDir == null || workDir.isBlank()) {
            return System.getProperty("java.io.tmpdir") + "/dls-task-work";
        }
        return workDir.trim();
    }

    /**
     * 单任务 stdout/stderr 采集上限（字符）。
     */
    private int outputMaxChars = 8192;

    private boolean timeoutWatchEnabled = true;

    private long timeoutScanIntervalMs = 30_000;

    private int timeoutScanBatchSize = 100;
}
