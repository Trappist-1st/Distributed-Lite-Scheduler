package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 配额预检查请求（设计稿 §4.3）。
 */
public record QuotaCheckRequest(
        @NotNull(message = "requestCpu 不能为空")
        @Min(value = 0, message = "requestCpu 不能为负")
        Integer requestCpu,

        @NotNull(message = "requestMemoryMb 不能为空")
        @Min(value = 0, message = "requestMemoryMb 不能为负")
        Integer requestMemoryMb,

        @NotNull(message = "requestGpu 不能为空")
        @Min(value = 0, message = "requestGpu 不能为负")
        Integer requestGpu
) {
}
