package com.imperium.distributed_lite_worker.runtime;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunningTaskRegistry {

    private final ConcurrentHashMap<Long, RunningTaskHandle> handles = new ConcurrentHashMap<>();

    /**
     * @return true 表示注册成功；false 表示已有同 ID 任务在跑（二次防御）
     */
    public boolean tryRegister(Long taskInstanceId, RunningTaskHandle handle) {
        if (taskInstanceId == null || handle == null) {
            return false;
        }
        return handles.putIfAbsent(taskInstanceId, handle) == null;
    }

    public void register(Long taskInstanceId, RunningTaskHandle handle) {
        tryRegister(taskInstanceId, handle);
    }

    public void unregister(Long taskInstanceId) {
        if (taskInstanceId != null) {
            handles.remove(taskInstanceId);
        }
    }

    public boolean cancel(Long taskInstanceId) {
        RunningTaskHandle handle = handles.remove(taskInstanceId);
        if (handle == null) {
            return false;
        }
        handle.cancelForcibly();
        return true;
    }
}
