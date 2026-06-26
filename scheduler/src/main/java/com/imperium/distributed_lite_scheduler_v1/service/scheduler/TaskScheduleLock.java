package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.config.properties.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 单任务调度互斥锁：防止多实例/多线程对同一 {@code task_instance} 重复调度。
 * <p>
 * 使用 {@code tryLock(wait, -1, SECONDS)} 启用 Redisson Watchdog 自动续期；
 * 调度路径结束在 {@link #release(RLock)} 中显式 unlock，避免固定 lease 过期导致双调度竞态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskScheduleLock {

    private final RedissonClient redissonClient;
    private final SchedulerProperties schedulerProperties;

    public RLock lockFor(long taskInstanceId) {
        return redissonClient.getLock(lockKey(taskInstanceId));
    }

    /**
     * 尝试获取任务调度锁（Watchdog 续期，无固定 lease）。
     *
     * @return true 表示当前线程已持有锁，可执行单任务调度路径
     */
    public boolean tryAcquire(RLock lock) {
        try {
            return lock.tryLock(
                    schedulerProperties.getTaskLockWaitSeconds(),
                    -1,
                    TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放任务调度锁（仅当当前线程持有时 unlock）。
     */
    public void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException e) {
            log.warn("释放任务调度锁时当前线程未持有锁 lockName={}", lock.getName());
        }
    }

    private String lockKey(long taskInstanceId) {
        return schedulerProperties.getTaskLockKeyPrefix() + taskInstanceId;
    }
}
