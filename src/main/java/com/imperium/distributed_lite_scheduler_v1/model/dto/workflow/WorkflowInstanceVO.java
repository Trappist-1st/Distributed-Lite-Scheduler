package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 工作流实例VO
 * 
 * 用于向前端返回工作流实例的详细信息
 */
@Data
public class WorkflowInstanceVO {
    
    /**
     * 工作流实例ID
     */
    private Long id;
    
    /**
     * 工作流ID
     */
    private Long workflowId;
    
    /**
     * 工作流名称
     */
    private String workflowName;
    
    /**
     * 实例编码
     */
    private String instanceCode;
    
    /**
     * 触发类型
     */
    private String triggerType;
    
    /**
     * 触发用户ID
     */
    private Long triggerUserId;
    
    /**
     * 触发用户名称
     */
    private String triggerUserName;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 状态描述
     */
    private String statusDescription;
    
    /**
     * 总任务数
     */
    private Integer totalTasks;
    
    /**
     * 成功任务数
     */
    private Integer successTasks;
    
    /**
     * 失败任务数
     */
    private Integer failedTasks;
    
    /**
     * 进度（百分比，0-100）
     */
    private Double progress;
    
    /**
     * 开始时间
     */
    private String startTime;
    
    /**
     * 结束时间
     */
    private String endTime;
    
    /**
     * 执行时长（毫秒）
     */
    private Long durationMs;
    
    /**
     * 执行时长（格式化字符串，如"2分30秒"）
     */
    private String durationFormatted;
    
    /**
     * 创建时间
     */
    private String createdAt;
    
    /**
     * 更新时间
     */
    private String updatedAt;
}
