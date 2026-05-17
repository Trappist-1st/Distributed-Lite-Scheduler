package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 工作流任务实例VO
 * 
 * 用于向前端返回工作流任务实例的详细信息
 */
@Data
public class WorkflowTaskInstanceVO {
    
    /**
     * 任务实例ID
     */
    private Long id;
    
    /**
     * 工作流实例ID
     */
    private Long workflowInstanceId;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 任务显示名称
     */
    private String taskDisplayName;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 状态描述
     */
    private String statusDescription;
    
    /**
     * 所属层级索引
     */
    private Integer layerIndex;
    
    /**
     * 重试次数
     */
    private Integer retryCount;
    
    /**
     * 关联的TaskInstance ID
     */
    private Long taskInstanceId;
    
    /**
     * 开始时间
     */
    private String startTime;
    
    /**
     * 结束时间
     */
    private String endTime;
    
    /**
     * 执行时长（秒）
     */
    private Integer durationSeconds;
    
    /**
     * 执行时长（格式化字符串，如"30秒"）
     */
    private String durationFormatted;
    
    /**
     * 退出码
     */
    private Integer exitCode;
    
    /**
     * 输出信息（摘要）
     */
    private String outputSummary;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 创建时间
     */
    private String createdAt;
    
    /**
     * 更新时间
     */
    private String updatedAt;
}
