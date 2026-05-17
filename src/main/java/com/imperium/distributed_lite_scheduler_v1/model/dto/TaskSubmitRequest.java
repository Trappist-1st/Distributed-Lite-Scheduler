package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务提交请求DTO
 * 
 * 用户仅需提供的字段：taskId、priority（可选）、parameters（可选）
 * 后端自动填充的字段：tenantId、submitUserId、taskInstanceId、submitTime、traceId
 * 从Task定义快照的字段：taskName、taskType、executorConfig、resourceRequirement
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSubmitRequest {
    
    @NotNull(message = "taskId不能为空")
    private Long taskId;
    
    @Min(value = 1, message = "priority最小值为1")
    @Max(value = 10, message = "priority最大值为10")
    private Integer priority;
    
    private Map<String, Object> parameters;

    private Long tenantId;
    private Long submitUserId;
    private Long taskInstanceId;
    private LocalDateTime submitTime;
    private String traceId;

    private String taskName;
    private String taskType;
    private String executorConfig;
    private String resourceRequirement;

    /** 工作流实例 ID（工作流编排提交时由引擎填充） */
    private Long workflowInstanceId;
}
