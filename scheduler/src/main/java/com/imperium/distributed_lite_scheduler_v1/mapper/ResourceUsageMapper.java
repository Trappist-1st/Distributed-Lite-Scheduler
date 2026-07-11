package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceUsage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceUsageMapper extends BaseMapper<ResourceUsage> {

    /**
     * 查询孤儿 RESERVED 记录：resource_usage 状态为 RESERVED，
     * 但对应的 task_instance 不存在或不处于 RUNNING 状态。
     * 触发场景：reserve() 事务提交后 JVM crash，updateStatusWithVersion() 未执行，
     * 导致任务卡死 PENDING 且资源永久占用。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT ru.* FROM resource_usage ru " +
            "LEFT JOIN task_instance ti ON ti.id = ru.task_instance_id " +
            "WHERE ru.status = 'RESERVED' " +
            "AND (ti.id IS NULL OR ti.status != 'RUNNING') " +
            "LIMIT #{limit}")
    java.util.List<ResourceUsage> selectOrphanedReserved(@org.apache.ibatis.annotations.Param("limit") int limit);
}
