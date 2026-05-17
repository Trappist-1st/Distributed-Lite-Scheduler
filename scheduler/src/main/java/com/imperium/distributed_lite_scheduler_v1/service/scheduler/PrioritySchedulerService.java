package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskWithPriority;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;

import java.util.List;

/**
 * 优先级调度器接口（P3-3）。
 */
public interface PrioritySchedulerService extends SchedulerService {

    /**
     * 扫描待调度任务并计算有效优先级。
     */
    List<TaskWithPriority> scanPendingTasksWithPriority(int limit);

    /**
     * 调度单个任务（优先级调度）。
     */
    @Override
    boolean scheduleTask(TaskInstance taskInstance);
}
