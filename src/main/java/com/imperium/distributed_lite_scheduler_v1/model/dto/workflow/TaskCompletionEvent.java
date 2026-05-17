package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务完成事件
 * 
 * 通过Redis Stream发送的事件消息，触发DAG引擎继续执行下一批任务
 * 
 * Stream Key: task-completion
 * Consumer Group: dag-engine-group
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskCompletionEvent {
    
    /**
     * 任务实例ID
     */
    private Long taskInstanceId;
    
    /**
     * 工作流实例ID（如果存在）
     */
    private Long workflowInstanceId;
    
    /**
     * 完成状态：SUCCESS/FAILED/TIMEOUT/CANCELLED
     */
    private String status;
    
    /**
     * 退出码
     */
    private Integer exitCode;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 执行时长（毫秒）
     */
    private Long durationMs;
    
    /**
     * 事件时间戳
     */
    private LocalDateTime timestamp;
}
