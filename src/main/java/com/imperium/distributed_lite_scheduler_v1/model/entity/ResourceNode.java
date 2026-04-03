package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源节点实体类
 * 对应表：resource_node
 */
@Data
@TableName("resource_node")
public class ResourceNode {
    
    /**
     * 资源节点ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 节点名称
     */
    private String nodeName;
    
    /**
     * 节点主机地址
     */
    private String nodeHost;
    
    /**
     * 节点端口
     */
    private Integer nodePort;
    
    /**
     * 节点类型：CPU/GPU/MIXED
     */
    private String nodeType;
    
    /**
     * 总CPU核心数
     */
    private Integer totalCpu;
    
    /**
     * 总内存（MB）
     */
    private Integer totalMemoryMb;
    
    /**
     * 总GPU卡数
     */
    private Integer totalGpu;
    
    /**
     * GPU型号（如：V100/A100）
     */
    private String gpuModel;
    
    /**
     * 可用CPU核心数
     */
    private Integer availableCpu;
    
    /**
     * 可用内存（MB）
     */
    private Integer availableMemoryMb;
    
    /**
     * 可用GPU卡数
     */
    private Integer availableGpu;
    
    /**
     * 状态：ONLINE/OFFLINE/MAINTENANCE
     */
    private String status;
    
    /**
     * 最后心跳时间
     */
    private LocalDateTime lastHeartbeatTime;
    
    /**
     * 标签（JSON，用于任务匹配）
     */
    private String labels;
    
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
