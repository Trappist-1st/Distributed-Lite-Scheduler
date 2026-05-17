package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 任务执行节点
 * 
 * 表示执行计划中的一个任务节点，包含任务的定义、状态、时间等信息
 * 
 * @author system
 * @since 2024-01-01
 */
@Data
public class TaskExecutionNode {
    
    /**
     * 任务名称（唯一标识）
     */
    private String taskName;
    
    /**
     * 任务定义（包含类型、命令、资源需求等）
     */
    private WorkflowTask taskDefinition;
    
    /**
     * 所属层级索引（从0开始）
     */
    private Integer layerIndex;
    
    /**
     * 执行状态
     */
    private TaskNodeStatus status;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 错误信息（失败时记录）
     */
    private String errorMessage;
    
    /**
     * 获取任务执行耗时（毫秒）
     * 
     * @return 执行耗时，如果未完成返回null
     */
    public Long getExecutionDurationMs() {
        // 1. 检查startTime和endTime是否都不为null
        // 2. 计算两者之间的时间差（毫秒）
        // 3. 如果未完成，返回null
        if (startTime == null || endTime == null) {
            return null;
        }
        return Duration.between(startTime, endTime).toMillis();
    }
    
    /**
     * 判断任务是否已完成（成功或失败）
     * 
     * @return true if completed
     */
    public boolean isCompleted() {
        // 检查status是SUCCESS或FAILED
        return status == TaskNodeStatus.SUCCESS || status == TaskNodeStatus.FAILED;
    }
}
