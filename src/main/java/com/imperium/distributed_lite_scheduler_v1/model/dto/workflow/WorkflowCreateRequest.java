package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建工作流请求
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Data
public class WorkflowCreateRequest {
    
    /**
     * 所属项目ID
     */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    
    /**
     * 工作流名称
     */
    @NotBlank(message = "工作流名称不能为空")
    private String workflowName;
    
    /**
     * 工作流编码（可选）
     */
    private String workflowCode;
    
    /**
     * 工作流描述
     */
    private String description;
    
    /**
     * DAG定义（JSON字符串）
     */
    @NotBlank(message = "DAG定义不能为空")
    private String dagJson;
    
    /**
     * 调度类型：MANUAL/CRON
     */
    private String scheduleType;
    
    /**
     * Cron表达式（scheduleType=CRON时必填）
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
}
