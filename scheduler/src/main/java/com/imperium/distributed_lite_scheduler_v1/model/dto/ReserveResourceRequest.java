package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 为任务实例预留资源（设计稿 §4.1）。
 */
public record ReserveResourceRequest(
        @NotNull(message = "租户ID不能为空")
        Long tenantId,

        @NotNull(message = "任务实例ID不能为空")
        Long taskInstanceId,

        @NotNull(message = "CPU 需求不能为空")
        @Min(value = 0, message = "CPU 需求不能为负")
        Integer cpu,

        @NotNull(message = "内存需求不能为空")
        @Min(value = 0, message = "内存需求不能为负")
        @Max(value = Integer.MAX_VALUE / 2, message = "内存需求过大")
        Integer memoryMb,

        @NotNull(message = "GPU 需求不能为空")
        @Min(value = 0, message = "GPU 需求不能为负")
        Integer gpu,

        @Size(max = 500, message = "候选节点列表过长")
        List<Long> candidateNodeIds
) {
}
