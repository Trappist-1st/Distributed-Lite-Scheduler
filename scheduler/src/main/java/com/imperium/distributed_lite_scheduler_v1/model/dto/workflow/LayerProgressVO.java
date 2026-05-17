package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 层级执行进度VO
 * 
 * 表示工作流DAG中某一层的执行进度
 */
@Data
public class LayerProgressVO {
    
    /**
     * 层级索引
     */
    private Integer layerIndex;
    
    /**
     * 层级名称
     * 例如：Layer 0, Layer 1
     */
    private String layerName;
    
    /**
     * 总任务数
     */
    private Integer totalTasks;
    
    /**
     * 已完成任务数
     */
    private Integer completedTasks;
    
    /**
     * 运行中任务数
     */
    private Integer runningTasks;
    
    /**
     * 失败任务数
     */
    private Integer failedTasks;
    
    /**
     * 等待中任务数
     */
    private Integer pendingTasks;
    
    /**
     * 跳过任务数
     */
    private Integer skippedTasks;
    
    /**
     * 层级进度（百分比，0-100）
     */
    private Double progress;
    
    /**
     * 层级状态
     * PENDING: 未开始
     * RUNNING: 执行中
     * COMPLETED: 已完成
     * FAILED: 失败
     */
    private String status;
}
