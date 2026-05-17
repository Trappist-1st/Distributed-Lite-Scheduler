package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 分页查询资源节点列表请求。
 */
public record ListResourceNodesRequest(
        @NotNull(message = "页码不能为空")
        @Min(value = 1, message = "页码从 1 开始")
        Integer page,

        @NotNull(message = "每页条数不能为空")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 100, message = "每页最多 100 条")
        Integer size,

        @Pattern(regexp = "^(ONLINE|OFFLINE|MAINTENANCE)$", message = "状态仅支持 ONLINE/OFFLINE/MAINTENANCE")
        String status,

        @Pattern(regexp = "^(CPU|GPU|MIXED)$", message = "节点类型仅支持 CPU/GPU/MIXED")
        String nodeType,

        @Size(max = 200, message = "关键词过长")
        String keyword
) {
}

