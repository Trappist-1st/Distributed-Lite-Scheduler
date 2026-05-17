package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskWithPriority;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;

import java.util.List;

/**
 * 资源感知调度器接口（P3-4）。
 * 在优先级调度基础上引入资源匹配维度。
 */
public interface ResourceAwareSchedulerService extends SchedulerService {

    /**
     * 扫描待调度任务并附带优先级信息。
     */
    List<TaskWithPriority> scanPendingTasksWithPriority(int limit);

    /**
     * 调度单个任务（资源感知调度）。
     */
    @Override
    boolean scheduleTask(TaskInstance taskInstance);
}
