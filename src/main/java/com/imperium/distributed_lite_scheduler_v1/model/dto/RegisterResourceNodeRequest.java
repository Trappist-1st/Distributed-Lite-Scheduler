package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Worker 节点注册请求。
 */
public record RegisterResourceNodeRequest(
        @NotBlank(message = "节点名称不能为空")
        @Size(max = 100, message = "节点名称过长")
        String nodeName,

        @NotBlank(message = "节点地址不能为空")
        @Size(max = 128, message = "节点地址过长")
        String nodeHost,

        @NotNull(message = "节点端口不能为空")
        @Min(value = 1, message = "端口范围无效")
        @Max(value = 65535, message = "端口范围无效")
        Integer nodePort,

        @NotBlank(message = "节点类型不能为空")
        @Pattern(regexp = "^(CPU|GPU|MIXED)$", message = "节点类型仅支持 CPU/GPU/MIXED")
        String nodeType,

        @NotNull(message = "总 CPU 不能为空")
        @Min(value = 0, message = "总 CPU 不能为负")
        Integer totalCpu,

        @NotNull(message = "总内存不能为空")
        @Min(value = 0, message = "总内存不能为负")
        Integer totalMemoryMb,

        @NotNull(message = "总 GPU 不能为空")
        @Min(value = 0, message = "总 GPU 不能为负")
        Integer totalGpu,

        @Size(max = 128, message = "GPU 型号过长")
        String gpuModel,

        @Size(max = 8000, message = "labels 过长")
        String labels
) {
}

