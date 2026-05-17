package com.imperium.distributed_lite_worker.runtime;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunningTaskRegistry {

    private final ConcurrentHashMap<Long, RunningTaskHandle> handles = new ConcurrentHashMap<>();

    public void register(Long taskInstanceId, RunningTaskHandle handle) {
        if (taskInstanceId != null && handle != null) {
            handles.put(taskInstanceId, handle);
        }
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
