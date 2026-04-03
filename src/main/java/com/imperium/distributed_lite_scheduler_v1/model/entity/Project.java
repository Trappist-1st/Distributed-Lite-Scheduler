package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目实体类
 * 对应表：project
 */
@Data
@TableName("project")
public class Project {
    
    /**
     * 项目ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 所属租户ID
     */
    private Long tenantId;
    
    /**
     * 项目名称
     */
    private String projectName;
    
    /**
     * 项目编码
     */
    private String projectCode;
    
    /**
     * 项目描述
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
     * 扩展配置（JSON）
     */
    private String extraConfig;
    
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
