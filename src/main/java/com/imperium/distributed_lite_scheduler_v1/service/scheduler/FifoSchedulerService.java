package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;

import java.util.List;

/**
 * FIFO 调度器接口。
 */
public interface FifoSchedulerService extends SchedulerService {

    /**
     * 扫描待调度任务（按 submitTime 升序）。
     */
    List<TaskInstance> scanPendingTasks(int limit);
}
