package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 释放任务占用资源（设计稿 §4.2）。
 */
public record ReleaseResourceRequest(
        Long taskInstanceId,
        Long usageId,

        @NotBlank(message = "释放原因不能为空")
        @Pattern(regexp = "^(SUCCESS|FAILED|CANCELLED|TIMEOUT)$", message = "原因仅支持 SUCCESS/FAILED/CANCELLED/TIMEOUT")
        String reason,

        @Size(max = 500, message = "备注过长")
        String remark
) {
}
