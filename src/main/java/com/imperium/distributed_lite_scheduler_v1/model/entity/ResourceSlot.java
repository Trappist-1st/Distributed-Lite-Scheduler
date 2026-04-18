package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源槽位：按节点 + 资源类型（CPU/GPU/MEMORY）一行，用于原子扣减与回补（P2-2）。
 */
@Data
@TableName("resource_slot")
public class ResourceSlot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long nodeId;

    /** CPU / GPU / MEMORY */
    private String resourceType;

    private Integer total;

    private Integer available;

    /** 对应库列 reserved_qty，表示已预留未释放量 */
    private Integer reservedQty;

    private Integer version;

    private LocalDateTime updatedAt;
}
