package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 分页查询任务列表。{@code projectId} 可选，用于限定在某个项目下查询。
 */
public record ListTasksRequest(
        @Positive(message = "项目 ID 无效")
        Long projectId,

        @NotNull(message = "页码不能为空")
        @Min(value = 1, message = "页码从 1 开始")
        Integer page,

        @NotNull(message = "每页条数不能为空")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 100, message = "每页最多 100 条")
        Integer size,

        @Size(max = 200, message = "关键词过长")
        String keyword,

        /** 任务定义状态：0-禁用，1-正常；{@code null} 表示不按状态过滤 */
        @Min(value = 0, message = "状态取值无效")
        @Max(value = 1, message = "状态取值无效")
        Integer status
) {
}
