package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源使用流水：一次预留对应一条记录，释放时更新状态与时间（P2-2）。
 */
@Data
@TableName("resource_usage")
public class ResourceUsage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long taskInstanceId;

    private Long nodeId;

    private Integer cpuUsed;

    private Integer memoryMbUsed;

    private Integer gpuUsed;

    /** RESERVED / RUNNING / RELEASED / FAILED */
    private String status;

    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime releasedAt;
}
