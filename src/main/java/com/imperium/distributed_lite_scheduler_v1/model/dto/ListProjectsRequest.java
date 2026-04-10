package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分页查询项目列表。{@code keyword} 可选，按名称或编码模糊匹配由服务层实现。
 */
public record ListProjectsRequest(
        @NotNull(message = "页码不能为空")
        @Min(value = 1, message = "页码从 1 开始")
        Integer page,

        @NotNull(message = "每页条数不能为空")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 100, message = "每页最多 100 条")
        Integer size,

        @Size(max = 200, message = "关键词过长")
        String keyword
) {
}
