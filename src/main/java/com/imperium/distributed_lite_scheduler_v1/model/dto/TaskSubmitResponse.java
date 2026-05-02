package com.imperium.distributed_lite_scheduler_v1.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 单个任务提交响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSubmitResponse {
    
    private Long taskInstanceId;
    
    private String status;
    
    private Instant estimatedStartTime;
}
