package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 更新租户配额上限（设计稿 §4.2）。
 */
public record UpdateResourceQuotaRequest(
        @NotNull(message = "maxCpu 不能为空")
        @Min(value = 0, message = "maxCpu 不能为负")
        Integer maxCpu,

        @NotNull(message = "maxMemoryMb 不能为空")
        @Min(value = 0, message = "maxMemoryMb 不能为负")
        Integer maxMemoryMb,

        @NotNull(message = "maxGpu 不能为空")
        @Min(value = 0, message = "maxGpu 不能为负")
        Integer maxGpu,

        @NotNull(message = "maxRunningTasks 不能为空")
        @Min(value = 0, message = "maxRunningTasks 不能为负")
        Integer maxRunningTasks,

        @NotNull(message = "maxPendingTasks 不能为空")
        @Min(value = 0, message = "maxPendingTasks 不能为负")
        Integer maxPendingTasks
) {
}
