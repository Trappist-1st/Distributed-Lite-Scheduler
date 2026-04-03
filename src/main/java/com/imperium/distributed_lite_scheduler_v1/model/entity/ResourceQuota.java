package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户资源配额实体类
 * 对应表：resource_quota
 */
@Data
@TableName("resource_quota")
public class ResourceQuota {
    
    /**
     * 配额ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
    /**
     * 最大CPU核心数
     */
    private Integer maxCpu;
    
    /**
     * 最大内存（MB）
     */
    private Integer maxMemoryMb;
    
    /**
     * 最大GPU卡数
     */
    private Integer maxGpu;
    
    /**
     * 最大并发运行任务数
     */
    private Integer maxRunningTasks;
    
    /**
     * 最大排队任务数
     */
    private Integer maxPendingTasks;
    
    /**
     * 已使用CPU核心数
     */
    private Integer usedCpu;
    
    /**
     * 已使用内存（MB）
     */
    private Integer usedMemoryMb;
    
    /**
     * 已使用GPU卡数
     */
    private Integer usedGpu;
    
    /**
     * 正在运行的任务数
     */
    private Integer runningTasks;
    
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
}
