package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量任务提交请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchTaskSubmitRequest {
    
    @NotEmpty(message = "tasks列表不能为空")
    @Size(max = 500, message = "单次批量提交不能超过500个任务")
    @Valid
    private List<TaskSubmitRequest> tasks;
}
