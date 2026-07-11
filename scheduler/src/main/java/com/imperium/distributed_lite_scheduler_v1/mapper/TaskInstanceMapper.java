package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskWithPriority;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstance> {

    // 批量插入任务实例
    @Insert({
            "<script>",
            "INSERT INTO task_instance (",
            "id, task_id, tenant_id, workflow_instance_id, instance_code, trigger_type, submit_user_id, status, priority,",
            "resource_requirement, executor_config, parameters, submit_time, resource_node_id, scheduled_time,",
            "start_time, end_time, duration_ms, exit_code, error_message, retry_count, version, created_at, updated_at",
            ") VALUES ",
            "<foreach collection='list' item='item' separator=','>",
            "(",
            "#{item.id}, #{item.taskId}, #{item.tenantId}, #{item.workflowInstanceId}, #{item.instanceCode}, #{item.triggerType},",
            "#{item.submitUserId}, #{item.status}, #{item.priority}, #{item.resourceRequirement}, #{item.executorConfig},",
            "#{item.parameters}, #{item.submitTime}, #{item.resourceNodeId}, #{item.scheduledTime}, #{item.startTime},",
            "#{item.endTime}, #{item.durationMs}, #{item.exitCode}, #{item.errorMessage}, #{item.retryCount},",
            "#{item.version}, #{item.createdAt}, #{item.updatedAt}",
            ")",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("list") List<TaskInstance> list);

    // 查询待调度的任务实例，按照提交时间排序，限制返回数量
    @Select({
            "SELECT ti.* ",
            "FROM task_instance ti ",
            "WHERE ti.status = 'PENDING' ",
            "  AND EXISTS (",
            "    SELECT 1 FROM task t ",
            "    WHERE t.id = ti.task_id ",
            "      AND t.status = 1 ",
            "      AND t.deleted = 0",
            "  ) ",
            "ORDER BY ti.submit_time ASC ",
            "LIMIT #{limit}"
    })
    List<TaskInstance> selectPendingTasks(@Param("limit") int limit);

    // 乐观锁更新任务实例状态，确保状态转换的原子性和一致性
    @Update({
            "UPDATE task_instance ",
            "SET status = #{toStatus}, ",
            "    resource_node_id = #{resourceNodeId}, ",
            "    start_time = #{startTime}, ",
            "    scheduled_time = #{scheduledTime}, ",
            "    version = version + 1, ",
            "    updated_at = NOW() ",
            "WHERE id = #{id} ",
            "  AND status = #{fromStatus} ",
            "  AND version = #{version}"
    })
    int updateStatusWithVersion(@Param("id") Long id,
                                @Param("fromStatus") String fromStatus,
                                @Param("toStatus") String toStatus,
                                @Param("version") Integer version,
                                @Param("resourceNodeId") Long resourceNodeId,
                                @Param("startTime") LocalDateTime startTime,
                                @Param("scheduledTime") LocalDateTime scheduledTime);

    // 查询待调度的任务实例，计算综合优先级（基于原始优先级和等待时间），按照综合优先级排序，限制返回数量
    @Select("SELECT ti.*, " +
            "       (#{priorityWeight} * (ti.priority / 10.0) + " +
            "        #{agingWeight} * (TIMESTAMPDIFF(SECOND, ti.submit_time, NOW()) / 3600.0)) AS computed_priority " +
            "FROM task_instance ti " +
            "WHERE ti.status = 'PENDING' " +
            "  AND EXISTS (" +
            "    SELECT 1 FROM task t " +
            "    WHERE t.id = ti.task_id " +
            "      AND t.status = 1 " +
            "      AND t.deleted = 0" +
            "  ) " +
            "ORDER BY computed_priority DESC, ti.submit_time " +
            "LIMIT #{limit}")
    List<TaskWithPriority> selectPendingTasksWithPriority(@Param("limit") int limit,
                                                          @Param("priorityWeight") double priorityWeight,
                                                          @Param("agingWeight") double agingWeight);

    /**
     * 该方法用于仅查询待调度任务的 ID 列表，按照与 {@link #selectPendingTasksWithPriority} 相同的优先级计算和排序逻辑。
     * 与 {@link #selectPendingTasksWithPriority} 相同的筛选与排序，仅返回 id 列表，便于稳定组装 {@link TaskWithPriority}。
     */
    @Select("SELECT ti.id FROM task_instance ti " +
            "WHERE ti.status = 'PENDING' " +
            "  AND EXISTS (" +
            "    SELECT 1 FROM task t " +
            "    WHERE t.id = ti.task_id " +
            "      AND t.status = 1 " +
            "      AND t.deleted = 0" +
            "  ) " +
            "ORDER BY (#{priorityWeight} * (ti.priority / 10.0) + " +
            "          #{agingWeight} * (TIMESTAMPDIFF(SECOND, ti.submit_time, NOW()) / 3600.0)) DESC, ti.submit_time " +
            "LIMIT #{limit}")
    List<Long> selectPendingTaskIdsByPriority(@Param("limit") int limit,
                                              @Param("priorityWeight") double priorityWeight,
                                              @Param("agingWeight") double agingWeight);

    /**
     * 查询已超时的 RUNNING 任务（timeout_seconds 来自 task 定义）。
     */
    @Select({
            "SELECT ti.* FROM task_instance ti ",
            "INNER JOIN task t ON t.id = ti.task_id ",
            "WHERE ti.status = 'RUNNING' ",
            "  AND ti.start_time IS NOT NULL ",
            "  AND t.timeout_seconds IS NOT NULL ",
            "  AND t.timeout_seconds > 0 ",
            "  AND TIMESTAMPDIFF(SECOND, ti.start_time, NOW()) > t.timeout_seconds ",
            "ORDER BY ti.start_time ASC ",
            "LIMIT #{limit}"
    })
    List<TaskInstance> selectTimedOutRunningTasks(@Param("limit") int limit);

    /**
     * 更新执行器心跳时间（仅更新 RUNNING 状态的任务，防止误更新已终态任务）。
     *
     * @param taskInstanceId 任务实例 ID
     * @return 更新行数（0 表示任务已不在 RUNNING 状态，执行器应停止）
     */
    @Update("UPDATE task_instance SET last_heartbeat_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{taskInstanceId} AND status = 'RUNNING'")
    int updateHeartbeat(@Param("taskInstanceId") Long taskInstanceId);

    /**
     * 查询心跳超时的 RUNNING 任务（"僵尸任务"）。
     * 触发场景：执行器 JVM crash / 节点宕机，导致心跳停止但 status 仍为 RUNNING。
     * heartbeatBefore：上次心跳时间早于此时刻的任务视为僵尸（典型值 = NOW() - 30s）。
     */
    @Select("SELECT ti.* FROM task_instance ti " +
            "WHERE ti.status = 'RUNNING' " +
            "AND ti.last_heartbeat_at IS NOT NULL " +
            "AND ti.last_heartbeat_at < #{heartbeatBefore} " +
            "ORDER BY ti.last_heartbeat_at ASC " +
            "LIMIT #{limit}")
    List<TaskInstance> selectZombieRunningTasks(
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("limit") int limit);

    /**
     * 查询指定节点上所有 RUNNING 状态的任务实例（节点宕机恢复用）。
     */
    @Select("SELECT * FROM task_instance WHERE status = 'RUNNING' AND resource_node_id = #{nodeId}")
    List<TaskInstance> selectRunningByNodeId(@Param("nodeId") Long nodeId);
}

