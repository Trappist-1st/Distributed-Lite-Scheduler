package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 预留成功后的分配结果（设计稿 §4.1）。
 */
public record ReserveResourceResponse(
        long nodeId,
        long usageId,
        int allocatedCpu,
        int allocatedMemoryMb,
        int allocatedGpu
) {
}
