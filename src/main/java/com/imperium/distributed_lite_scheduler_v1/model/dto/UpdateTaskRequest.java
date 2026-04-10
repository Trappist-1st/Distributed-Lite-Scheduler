package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新任务请求。字段均为可选：{@code null} 表示不修改该项。
 */
public record UpdateTaskRequest(
        @Size(min = 1, max = 256, message = "任务名称长度为 1～256")
        String taskName,

        @Size(min = 1, max = 128, message = "任务编码长度为 1～128")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "任务编码仅允许字母、数字、下划线、连字符")
        String taskCode,

        @Size(max = 32, message = "任务类型过长")
        String taskType,

        @Size(max = 16000, message = "执行器配置过长")
        String executorConfig,

        @Size(max = 32, message = "调度类型过长")
        String scheduleType,

        @Size(max = 128, message = "Cron 表达式过长")
        String cronExpression,

        @Min(value = 1, message = "超时时间至少 1 秒")
        @Max(value = 86400 * 365, message = "超时时间超出允许范围")
        Integer timeoutSeconds,

        @Min(value = 0, message = "重试次数不能为负")
        @Max(value = 100, message = "重试次数超出允许范围")
        Integer retryTimes,

        @Min(value = 0, message = "重试间隔不能为负")
        @Max(value = 86400, message = "重试间隔超出允许范围")
        Integer retryInterval,

        @Min(value = 1, message = "优先级最小为 1")
        @Max(value = 10, message = "优先级最大为 10")
        Integer priority,

        @Size(max = 8000, message = "资源需求过长")
        String resourceRequire,

        @Min(value = 0, message = "失败告警标记取值无效")
        @Max(value = 1, message = "失败告警标记取值无效")
        Integer alertOnFailure,

        @Min(value = 0, message = "超时告警标记取值无效")
        @Max(value = 1, message = "超时告警标记取值无效")
        Integer alertOnTimeout,

        @Size(max = 2000, message = "描述过长")
        String description,

        @Min(value = 0, message = "状态取值无效")
        @Max(value = 1, message = "状态取值无效")
        Integer status
) {
}
