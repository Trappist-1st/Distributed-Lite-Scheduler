package com.imperium.distributed_lite_scheduler_v1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/**
 * 调度器策略与 Leader 选举配置。
 * <p>
 * 同一部署中仅应启用一种 {@link #strategy}；多 scheduler 实例通过 {@link #leaderLockKey} 竞争 Leader。
 */
@Data
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    private static final Set<String> ALLOWED_STRATEGIES = Set.of("fifo", "priority", "resource-aware");

    /**
     * 启用的调度策略：fifo | priority | resource-aware
     */
    private String strategy = "resource-aware";

    /**
     * 调度主循环间隔（毫秒），使用 fixedDelay（上一轮结束后再等待）。
     */
    private long loopIntervalMs = 5000L;

    /**
     * Leader 分布式锁 key（所有 scheduler 实例、任意策略共用，同时刻仅一种策略 Bean 存在）。
     */
    private String leaderLockKey = "scheduler:leader:lock";

    /**
     * 已废弃：Leader 锁使用 Watchdog（{@code tryLock(0, -1, MILLISECONDS)}），不再读此租约。
     * 保留配置项仅为兼容旧环境变量，将在后续版本移除。
     */
    @Deprecated
    private int leaderLockLeaseSeconds = 30;

    /**
     * 单任务调度锁获取最长等待（秒）；持锁后 Watchdog 续期，无固定 lease。
     */
    private int taskLockWaitSeconds = 1;

    /**
     * 单任务调度锁 key 前缀，完整 key 为 {@code prefix + taskInstanceId}。
     */
    private String taskLockKeyPrefix = "task:schedule:lock:";

    public void setStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("scheduler.strategy 不能为空");
        }
        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STRATEGIES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "scheduler.strategy 无效: " + strategy + "，仅支持 fifo | priority | resource-aware");
        }
        this.strategy = normalized;
    }

    public boolean isFifo() {
        return "fifo".equals(strategy);
    }

    public boolean isPriority() {
        return "priority".equals(strategy);
    }

    public boolean isResourceAware() {
        return "resource-aware".equals(strategy);
    }
}
