package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 租户配额与使用率快照（设计稿 §4.1）。
 */
public record ResourceQuotaDetailResponse(
        long tenantId,
        int maxCpu,
        int maxMemoryMb,
        int maxGpu,
        int maxRunningTasks,
        int maxPendingTasks,
        int usedCpu,
        int usedMemoryMb,
        int usedGpu,
        int runningTasks,
        double cpuUtilization,
        double memoryUtilization,
        double gpuUtilization,
        double runningTasksUtilization
) {
}
