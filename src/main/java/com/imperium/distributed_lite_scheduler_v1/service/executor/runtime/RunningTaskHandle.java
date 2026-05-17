package com.imperium.distributed_lite_scheduler_v1.service.executor.runtime;

/**
 * 可取消的运行中任务句柄。
 */
public interface RunningTaskHandle {

    void cancelForcibly();
}
