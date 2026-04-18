package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceSlot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 资源槽位 Mapper；扣减/回补使用单条 SQL 保证原子性（设计稿 §6）。
 */
@Mapper
public interface ResourceSlotMapper extends BaseMapper<ResourceSlot> {

    /**
     * 单语句插入三行槽位（若 uk 已存在则忽略），减少并发下「逐行 select + insert」的竞态窗口。
     */
    @Insert("""
            INSERT IGNORE INTO resource_slot (node_id, resource_type, total, available, reserved_qty, version)
            VALUES (#{nodeId}, 'CPU', #{totalCpu}, #{availCpu}, 0, 0),
                   (#{nodeId}, 'MEMORY', #{totalMem}, #{availMem}, 0, 0),
                   (#{nodeId}, 'GPU', #{totalGpu}, #{availGpu}, 0, 0)
            """)
    int insertSlotsIfAbsent(
            @Param("nodeId") long nodeId,
            @Param("totalCpu") int totalCpu,
            @Param("availCpu") int availCpu,
            @Param("totalMem") int totalMem,
            @Param("availMem") int availMem,
            @Param("totalGpu") int totalGpu,
            @Param("availGpu") int availGpu);

    /**
     * 条件扣减：仅当 available &gt;= amount 时生效，防止超分与负数。
     */
    @Update("""
            UPDATE resource_slot
            SET available = available - #{amount},
                reserved_qty = reserved_qty + #{amount},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE node_id = #{nodeId}
              AND resource_type = #{resourceType}
              AND available >= #{amount}
            """)
    int decreaseIfEnough(
            @Param("nodeId") Long nodeId,
            @Param("resourceType") String resourceType,
            @Param("amount") int amount);

    /**
     * 释放回补：available 不超过 total；reserved_qty 不低于 0。
     */
    @Update("""
            UPDATE resource_slot
            SET available = LEAST(total, available + #{amount}),
                reserved_qty = GREATEST(0, reserved_qty - #{amount}),
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE node_id = #{nodeId}
              AND resource_type = #{resourceType}
            """)
    int increaseAvailable(
            @Param("nodeId") Long nodeId,
            @Param("resourceType") String resourceType,
            @Param("amount") int amount);
}
