package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建租户请求。与表 {@code tenant} 对齐；{@code maxProjects}/{@code maxTasks} 不传时由服务层使用默认值。
 */
public record CreateTenantRequest(
        @NotBlank(message = "租户名称不能为空")
        @Size(min = 1, max = 100, message = "租户名称长度为 1～100")
        String tenantName,

        @NotBlank(message = "租户编码不能为空")
        @Size(min = 3, max = 50, message = "租户编码长度为 3～50")
        @Pattern(regexp = "^[a-z0-9_]{3,50}$", message = "租户编码仅允许小写字母、数字、下划线")
        String tenantCode,

        @Size(max = 2000, message = "描述过长")
        String description,

        /** 最大项目数；{@code null} 表示使用系统默认（如 10） */
        @Min(value = 1, message = "最大项目数至少为 1")
        @Max(value = 1_000_000, message = "最大项目数超出允许范围")
        Integer maxProjects,

        /** 最大任务数；{@code null} 表示使用系统默认（如 1000） */
        @Min(value = 1, message = "最大任务数至少为 1")
        @Max(value = 10_000_000, message = "最大任务数超出允许范围")
        Integer maxTasks,

        /** 过期时间；{@code null} 表示永久有效 */
        LocalDateTime expireTime
) {
}
