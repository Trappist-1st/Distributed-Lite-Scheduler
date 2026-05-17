package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

/**
 * 任务节点状态枚举
 * 
 * @author system
 * @since 2024-01-01
 */
public enum TaskNodeStatus {
    
    /**
     * 等待执行 - 任务尚未开始
     */
    PENDING,
    
    /**
     * 执行中 - 任务正在运行
     */
    RUNNING,
    
    /**
     * 执行成功 - 任务已成功完成
     */
    SUCCESS,
    
    /**
     * 执行失败 - 任务执行出错
     */
    FAILED,
    
    /**
     * 跳过 - 条件不满足，任务被跳过
     */
    SKIPPED
}
