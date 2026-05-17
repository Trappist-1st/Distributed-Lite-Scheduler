package com.imperium.distributed_lite_scheduler_v1.service.executor.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪本机正在执行的任务，支持取消与超时协作。
 */
@Slf4j
@Component
public class RunningTaskRegistry {

    private final ConcurrentHashMap<Long, RunningTaskHandle> handles = new ConcurrentHashMap<>();

    public void register(Long taskInstanceId, RunningTaskHandle handle) {
        if (taskInstanceId == null || handle == null) {
            return;
        }
        handles.put(taskInstanceId, handle);
    }

    public void unregister(Long taskInstanceId) {
        if (taskInstanceId != null) {
            handles.remove(taskInstanceId);
        }
    }

    /**
     * @return true 表示找到并尝试取消
     */
    public boolean cancel(Long taskInstanceId) {
        RunningTaskHandle handle = handles.remove(taskInstanceId);
        if (handle == null) {
            return false;
        }
        handle.cancelForcibly();
        return true;
    }

    public boolean isTracked(Long taskInstanceId) {
        return taskInstanceId != null && handles.containsKey(taskInstanceId);
    }
}
