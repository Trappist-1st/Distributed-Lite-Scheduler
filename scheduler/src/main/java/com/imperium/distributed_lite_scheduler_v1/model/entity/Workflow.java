package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义实体类
 * 对应表：workflow
 */
@Data
@TableName("workflow")
public class Workflow {
    
    /**
     * 工作流ID
     */
    @TableId(value = "id", type = IdType.AUTO)
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
     * DAG定义（JSON格式）
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
     * 下次调度时间
     */
    private LocalDateTime nextScheduleTime;
    
    /**
     * 工作流超时时间（秒）
     */
    private Integer timeoutSeconds;
    
    /**
     * 失败时告警
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
     * 版本号（乐观锁）
     */
    @Version
    private Integer version;
    
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
    
    /**
     * 删除标记
     */
    @TableLogic
    private Integer deleted;
}
