package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceNodeMapper extends BaseMapper<ResourceNode> {

    /**
     * 查询心跳过期的 ONLINE 节点（疑似宕机节点）。
     * heartbeatBefore：最后心跳时间早于此时刻的节点视为疑似宕机（典型值 = NOW() - 30s）。
     * NodeHeartbeatWatchdog 用此结果触发节点标记 OFFLINE 和任务恢复流程。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM resource_node " +
            "WHERE status = 'ONLINE' " +
            "AND last_heartbeat_time IS NOT NULL " +
            "AND last_heartbeat_time < #{heartbeatBefore} " +
            "LIMIT #{limit}")
    java.util.List<ResourceNode> selectDeadNodes(
            @org.apache.ibatis.annotations.Param("heartbeatBefore") java.time.LocalDateTime heartbeatBefore,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 更新节点状态为 OFFLINE。
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE resource_node SET status = 'OFFLINE', updated_at = NOW() " +
            "WHERE id = #{nodeId} AND status = 'ONLINE'")
    int markOffline(@org.apache.ibatis.annotations.Param("nodeId") Long nodeId);
}

