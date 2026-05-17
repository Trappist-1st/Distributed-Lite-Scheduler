package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户实体类
 * 对应表：tenant
 */
@Data
@TableName("tenant")
public class Tenant {
    
    /**
     * 租户ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 租户名称
     */
    private String tenantName;
    
    /**
     * 租户编码（唯一标识）
     */
    private String tenantCode;
    
    /**
     * 所有者用户ID
     */
    private Long ownerUserId;
    
    /**
     * 租户描述
     */
    private String description;
    
    /**
     * 状态：0-禁用，1-正常
     */
    private Integer status;
    
    /**
     * 过期时间（NULL表示永久）
     */
    private LocalDateTime expireTime;
    
    /**
     * 最大项目数限制
     */
    private Integer maxProjects;
    
    /**
     * 最大任务数限制
     */
    private Integer maxTasks;
    
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
