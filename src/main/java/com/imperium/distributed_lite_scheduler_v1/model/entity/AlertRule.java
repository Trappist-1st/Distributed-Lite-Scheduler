package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则实体类
 * 对应表：alert_rule
 */
@Data
@TableName("alert_rule")
public class AlertRule {
    
    /**
     * 告警规则ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * 规则类型：TASK_FAILURE/TASK_TIMEOUT/RESOURCE_SHORTAGE
     */
    private String ruleType;
    
    /**
     * 目标类型：PROJECT/TASK/WORKFLOW
     */
    private String targetType;
    
    /**
     * 目标ID（NULL表示全局）
     */
    private Long targetId;
    
    /**
     * 条件配置（JSON）
     */
    private String conditionConfig;
    
    /**
     * 通知渠道（JSON）：EMAIL/SMS/WEBHOOK
     */
    private String notificationChannels;
    
    /**
     * 通知用户ID列表（JSON）
     */
    private String notificationUsers;
    
    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;
    
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
