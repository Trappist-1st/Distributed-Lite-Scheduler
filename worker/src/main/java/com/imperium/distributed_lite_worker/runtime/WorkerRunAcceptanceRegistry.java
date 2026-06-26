package com.imperium.distributed_lite_worker.runtime;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录已接受、尚未执行完毕的 {@code taskInstanceId}，防止 Scheduler 重复 POST 导致双进程执行。
 * <p>
 * 接受发生在 HTTP 边界；{@link #release(long)} 在异步执行路径 {@code finally} 中调用，
 * 以便同一实例在调度侧重试（PENDING 再次下发）时可重新接受。
 */
@Component
public class WorkerRunAcceptanceRegistry {

    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * @return true 表示首次接受；false 表示该任务已在执行或排队中
     */
    public boolean tryAccept(long taskInstanceId) {
        return inFlight.putIfAbsent(taskInstanceId, Boolean.TRUE) == null;
    }

    public boolean isInFlight(long taskInstanceId) {
        return inFlight.containsKey(taskInstanceId);
    }

    public void release(long taskInstanceId) {
        inFlight.remove(taskInstanceId);
    }
}
