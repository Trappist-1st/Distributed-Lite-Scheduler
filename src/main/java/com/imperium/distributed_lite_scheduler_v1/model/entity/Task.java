package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务定义实体类
 * 对应表：task
 */
@Data
@TableName("task")
public class Task {
    
    /**
     * 任务ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 所属项目ID
     */
    private Long projectId;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 任务编码
     */
    private String taskCode;
    
    /**
     * 任务类型：SHELL/PYTHON/DOCKER/K8S_JOB
     */
    private String taskType;
    
    /**
     * 执行器配置（JSON）
     */
    private String executorConfig;
    
    /**
     * 调度类型：MANUAL/CRON/DEPENDENCY
     */
    private String scheduleType;
    
    /**
     * Cron表达式
     */
    private String cronExpression;
    
    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds;
    
    /**
     * 失败重试次数
     */
    private Integer retryTimes;
    
    /**
     * 重试间隔（秒）
     */
    private Integer retryInterval;
    
    /**
     * 优先级：1-10
     */
    private Integer priority;
    
    /**
     * 资源需求（JSON）
     */
    private String resourceRequire;
    
    /**
     * 失败时告警：0-否，1-是
     */
    private Integer alertOnFailure;
    
    /**
     * 超时时告警：0-否，1-是
     */
    private Integer alertOnTimeout;
    
    /**
     * 任务描述
     */
    private String description;
    
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
