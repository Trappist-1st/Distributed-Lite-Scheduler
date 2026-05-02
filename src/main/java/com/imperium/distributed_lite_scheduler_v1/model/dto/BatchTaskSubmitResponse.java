package com.imperium.distributed_lite_scheduler_v1.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量任务提交响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchTaskSubmitResponse {
    
    // 成功提交的任务数量
    private Integer successCount;
    
    // 成功提交的任务实例ID列表
    private List<Long> taskInstanceIds;
}
