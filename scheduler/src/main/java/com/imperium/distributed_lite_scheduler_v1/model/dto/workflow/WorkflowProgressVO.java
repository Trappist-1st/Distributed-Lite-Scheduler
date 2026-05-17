package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流实例进度VO
 * 
 * 用于向前端返回工作流实例的执行进度信息
 */
@Data
public class WorkflowProgressVO {
    
    /**
     * 工作流实例ID
     */
    private Long instanceId;
    
    /**
     * 工作流名称
     */
    private String workflowName;
    
    /**
     * 实例名称
     */
    private String instanceName;
    
    /**
     * 执行状态
     */
    private WorkflowInstanceStatus status;
    
    /**
     * 总进度（百分比，0-100）
     */
    private Double progress;
    
    /**
     * 总任务数
     */
    private Integer totalTasks;
    
    /**
     * 已完成任务数
     */
    private Integer completedTasks;
    
    /**
     * 失败任务数
     */
    private Integer failedTasks;
    
    /**
     * 跳过任务数
     */
    private Integer skippedTasks;
    
    /**
     * 运行中任务数
     */
    private Integer runningTasks;
    
    /**
     * 等待中任务数
     */
    private Integer pendingTasks;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 执行时长（秒）
     */
    private Integer durationSeconds;
    
    /**
     * 预计剩余时间（秒）
     * 基于已完成任务的平均时长估算
     */
    private Integer estimatedRemainingSeconds;
    
    /**
     * 各层执行进度
     */
    private List<LayerProgressVO> layers;
}
