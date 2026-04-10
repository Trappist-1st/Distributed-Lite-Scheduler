package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。与表 {@code project} 字段对齐；租户、创建者由服务层填充。
 */
public record CreateProjectRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(min = 1, max = 128, message = "项目名称长度为 1～128")
        String projectName,

        @NotBlank(message = "项目编码不能为空")
        @Size(min = 1, max = 64, message = "项目编码长度为 1～64")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "项目编码仅允许字母、数字、下划线、连字符")
        String projectCode,

        @Size(max = 2000, message = "描述过长")
        String description,

        /** 扩展配置（JSON）；不传表示无 */
        @Size(max = 8000, message = "扩展配置过长")
        String extraConfig,

        /** 状态：0-禁用，1-正常；{@code null} 表示使用默认（如 1） */
        @Min(value = 0, message = "状态取值无效")
        @Max(value = 1, message = "状态取值无效")
        Integer status
) {
}
