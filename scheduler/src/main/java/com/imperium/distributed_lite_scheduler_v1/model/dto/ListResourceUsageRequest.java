package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 分页查询资源使用流水（设计稿 §4.3）。
 */
public record ListResourceUsageRequest(
        @NotNull(message = "租户ID不能为空")
        Long tenantId,

        @NotNull(message = "页码不能为空")
        @Min(value = 1, message = "页码从 1 开始")
        Integer page,

        @NotNull(message = "每页条数不能为空")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 100, message = "每页最多 100 条")
        Integer size,

        @Pattern(regexp = "^(RESERVED|RUNNING|RELEASED|FAILED|CANCELLED)$", message = "状态仅支持 RESERVED/RUNNING/RELEASED/FAILED/CANCELLED")
        String status,

        Long nodeId
) {
}
