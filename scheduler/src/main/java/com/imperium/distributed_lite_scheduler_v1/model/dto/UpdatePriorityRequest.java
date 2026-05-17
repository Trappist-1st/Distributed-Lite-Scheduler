package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 动态调整任务优先级请求（P3-3）。
 */
@Data
public class UpdatePriorityRequest {
    @NotNull(message = "priority不能为空")
    @Min(value = 1, message = "priority最小值为1")
    @Max(value = 10, message = "priority最大值为10")
    private Integer priority;

    @NotBlank(message = "reason不能为空")
    private String reason;
}
