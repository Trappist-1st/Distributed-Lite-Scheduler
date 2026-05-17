package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 资源节点心跳上报请求。
 */
public record ResourceHeartbeatRequest(
        @NotBlank(message = "节点地址不能为空")
        @Size(max = 128, message = "节点地址过长")
        String nodeHost,

        @NotNull(message = "节点端口不能为空")
        @Min(value = 1, message = "端口范围无效")
        @Max(value = 65535, message = "端口范围无效")
        Integer nodePort,

        @Min(value = 0, message = "可用 CPU 不能为负")
        Integer availableCpu,

        @Min(value = 0, message = "可用内存不能为负")
        Integer availableMemoryMb,

        @Min(value = 0, message = "可用 GPU 不能为负")
        Integer availableGpu,

        @Pattern(regexp = "^(ONLINE|OFFLINE|MAINTENANCE)$", message = "状态仅支持 ONLINE/OFFLINE/MAINTENANCE")
        String status
) {
}

