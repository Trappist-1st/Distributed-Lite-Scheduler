package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 性能指标快照实体类
 * 对应表：metric_snapshot
 */
@Data
@TableName("metric_snapshot")
public class MetricSnapshot {
    
    /**
     * 快照ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 资源类型：NODE/TASK_INSTANCE
     */
    private String resourceType;
    
    /**
     * 资源ID
     */
    private Long resourceId;
    
    /**
     * 指标类型：CPU_USAGE/MEMORY_USAGE/GPU_USAGE
     */
    private String metricType;
    
    /**
     * 指标值
     */
    private BigDecimal metricValue;
    
    /**
     * 快照时间
     */
    private LocalDateTime snapshotTime;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
