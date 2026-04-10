package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务实例实体类
 * 对应表：task_instance
 */
@Data
@TableName("task_instance")
public class TaskInstance {
    
    /**
     * 任务实例ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 任务定义ID
     */
    private Long taskId;
    
    /**
     * 所属工作流实例ID（NULL表示独立任务）
     */
    private Long workflowInstanceId;
    
    /**
     * 实例唯一标识（用于幂等性）
     */
    private String instanceCode;
    
    /**
     * 触发类型：MANUAL/CRON/DEPENDENCY/API
     */
    private String triggerType;
    
    /**
     * 触发用户ID（手动触发时）
     */
    private Long triggerUserId;
    
    /**
     * 状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT
     */
    private String status;
    
    /**
     * 优先级
     */
    private Integer priority;
    
    /**
     * 分配的资源节点ID
     */
    private Long resourceNodeId;
    
    /**
     * 预定调度时间
     */
    private LocalDateTime scheduledTime;
    
    /**
     * 实际开始时间
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
     * 退出码
     */
    private Integer exitCode;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 已重试次数
     */
    private Integer retryCount;
    
    /**
     * 版本号（乐观锁）  这个太妙了，乐观锁可以防止并发修改导致的数据不一致问题，在分布式系统中非常有用。
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
}
