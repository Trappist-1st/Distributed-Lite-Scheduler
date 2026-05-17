package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工作流视图对象
 * 
 * 用于前端展示的工作流信息
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Data
public class WorkflowVO {
    
    /**
     * 工作流ID
     */
    private Long id;
    
    /**
     * 所属项目ID
     */
    private Long projectId;
    
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
     * DAG定义（已解析）
     */
    private WorkflowDAG dag;
    
    /**
     * 调度类型：MANUAL/CRON
     */
    private String scheduleType;
    
    /**
     * Cron表达式
     */
    private String cronExpression;
    
    /**
     * 下次调度时间
     */
    private LocalDateTime nextScheduleTime;
    
    /**
     * 工作流超时时间（秒）
     */
    private Integer timeoutSeconds;
    
    /**
     * 失败时告警：0-否，1-是
     */
    private Integer alertOnFailure;
    
    /**
     * 创建者用户ID
     */
    private Long creatorUserId;
    
    /**
     * 状态：0-禁用，1-正常
     */
    private Integer status;
    
    /**
     * 版本号
     */
    private Integer version;
    
    /**
     * 任务总数（从DAG中计算）
     */
    private Integer taskCount;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
