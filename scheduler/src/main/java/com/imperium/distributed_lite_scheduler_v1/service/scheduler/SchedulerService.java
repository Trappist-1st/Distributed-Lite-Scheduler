package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;

/**
 * 调度器统一接口。
 */
public interface SchedulerService {

    /**
     * 调度主循环（由定时任务触发）。
     */
    void scheduleLoop();

    /**
     * 调度单个任务。
     */
    boolean scheduleTask(TaskInstance taskInstance);
}
