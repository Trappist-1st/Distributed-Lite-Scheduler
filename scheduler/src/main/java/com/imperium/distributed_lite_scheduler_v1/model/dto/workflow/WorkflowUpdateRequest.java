package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 更新工作流请求
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Data
public class WorkflowUpdateRequest {
    
    /**
     * 工作流名称
     */
    private String workflowName;
    
    /**
     * 工作流编码
     */
    private String workflowCode;
    
    /**
     * 工作流描述
     */
    private String description;
    
    /**
     * DAG定义（JSON字符串）
     */
    private String dagJson;
    
    /**
     * 调度类型：MANUAL/CRON
     */
    private String scheduleType;
    
    /**
     * Cron表达式
     */
    private String cronExpression;
    
    /**
     * 工作流超时时间（秒）
     */
    private Integer timeoutSeconds;
    
    /**
     * 失败时告警：0-否，1-是
     */
    private Integer alertOnFailure;
    
    /**
     * 状态：0-禁用，1-正常
     */
    private Integer status;
}
