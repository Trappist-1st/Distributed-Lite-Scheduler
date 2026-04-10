package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新项目请求。字段均为可选：{@code null} 表示不修改该项。
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 128, message = "项目名称长度为 1～128")
        String projectName,

        @Size(min = 1, max = 64, message = "项目编码长度为 1～64")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "项目编码仅允许字母、数字、下划线、连字符")
        String projectCode,

        @Size(max = 2000, message = "描述过长")
        String description,

        @Size(max = 8000, message = "扩展配置过长")
        String extraConfig,

        @Min(value = 0, message = "状态取值无效")
        @Max(value = 1, message = "状态取值无效")
        Integer status
) {
}
