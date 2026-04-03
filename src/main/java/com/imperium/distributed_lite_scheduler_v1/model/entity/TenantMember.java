package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户成员实体类
 * 对应表：tenant_member
 */
@Data
@TableName("tenant_member")
public class TenantMember {
    
    /**
     * 成员ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 角色：OWNER/ADMIN/MEMBER/GUEST
     */
    private String role;
    
    /**
     * 加入时间
     */
    private LocalDateTime joinTime;
    
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
