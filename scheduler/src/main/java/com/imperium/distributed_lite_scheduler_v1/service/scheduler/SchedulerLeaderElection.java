package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.config.properties.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 调度器 Leader 选举：同一策略的多实例竞争同一把 Redis 锁。
 * <p>
 * 使用 {@code tryLock(0, -1, MILLISECONDS)} 启用 Redisson Watchdog 自动续期；
 * 每轮调度结束在 {@link #releaseLeadership()} 中显式 unlock，避免依赖固定 lease 过期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerLeaderElection {

    private final RedissonClient redissonClient;
    private final SchedulerProperties schedulerProperties;

    private volatile boolean leader;

    /**
     * 尝试成为本轮调度 Leader（Watchdog 续期，无固定 lease）。
     *
     * @return true 表示当前实例持有 Leader 锁，可执行本轮 scheduleLoop
     */
    public boolean tryAcquireLeadership() {
        RLock lock = leaderLock();
        try {
            boolean acquired = lock.tryLock(0, -1, TimeUnit.MILLISECONDS);
            if (acquired && !leader) {
                leader = true;
                log.info(
                        "成为调度 Leader strategy={} lockKey={} (watchdog)",
                        schedulerProperties.getStrategy(),
                        schedulerProperties.getLeaderLockKey());
            } else if (!acquired && leader) {
                leader = false;
                log.warn(
                        "失去调度 Leader 身份 strategy={} lockKey={}",
                        schedulerProperties.getStrategy(),
                        schedulerProperties.getLeaderLockKey());
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放本轮 Leader 锁（仅当当前线程持有时 unlock）。
     */
    public void releaseLeadership() {
        RLock lock = leaderLock();
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug(
                        "已释放调度 Leader 锁 strategy={} lockKey={}",
                        schedulerProperties.getStrategy(),
                        schedulerProperties.getLeaderLockKey());
            }
        } catch (IllegalMonitorStateException e) {
            log.warn(
                    "释放 Leader 锁时当前线程未持有锁 strategy={} lockKey={}",
                    schedulerProperties.getStrategy(),
                    schedulerProperties.getLeaderLockKey());
        } finally {
            leader = false;
        }
    }

    public boolean isLeader() {
        return leader;
    }

    private RLock leaderLock() {
        return redissonClient.getLock(schedulerProperties.getLeaderLockKey());
    }
}
