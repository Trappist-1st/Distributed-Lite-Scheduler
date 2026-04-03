package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流实例实体类
 * 对应表：workflow_instance
 */
@Data
@TableName("workflow_instance")
public class WorkflowInstance {
    
    /**
     * 工作流实例ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 工作流定义ID
     */
    private Long workflowId;
    
    /**
     * 实例唯一标识
     */
    private String instanceCode;
    
    /**
     * 触发类型：MANUAL/CRON/API
     */
    private String triggerType;
    
    /**
     * 触发用户ID
     */
    private Long triggerUserId;
    
    /**
     * 状态：RUNNING/SUCCESS/FAILED/CANCELLED
     */
    private String status;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 执行时长（毫秒）
     */
    private Long durationMs;
    
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
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
