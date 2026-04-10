package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 内部接口：任务实例状态转换请求。
 */
public record InternalTaskInstanceStatusTransitionRequest(
        @NotBlank(message = "fromStatus 不能为空")
        @Size(max = 32, message = "fromStatus 过长")
        String fromStatus,

        @NotBlank(message = "toStatus 不能为空")
        @Size(max = 32, message = "toStatus 过长")
        String toStatus,

        @NotBlank(message = "triggerSource 不能为空")
        @Size(max = 32, message = "triggerSource 过长")
        @Pattern(regexp = "^(SCHEDULER|WORKER|SYSTEM|API)$", message = "triggerSource 仅支持 SCHEDULER/WORKER/SYSTEM/API")
        String triggerSource,

        @Size(max = 500, message = "reason 过长")
        String reason,

        @Min(value = 0, message = "operatorUserId 不能为负")
        Long operatorUserId,

        Integer exitCode,

        @Size(max = 2000, message = "errorMessage 过长")
        String errorMessage
) {
}

