package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.config.properties.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 唯一的调度定时入口：根据 {@link SchedulerProperties#strategy} 仅存在一个 {@link SchedulerService} 实现。
 * <p>
 * Leader 锁在本类统一获取/释放（Watchdog + finally unlock），各 {@link SchedulerService} 实现不再重复选举。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerLoopRunner {

    private final SchedulerService schedulerService;
    private final SchedulerProperties schedulerProperties;
    private final SchedulerLeaderElection schedulerLeaderElection;

    @EventListener(ApplicationReadyEvent.class)
    public void logActiveStrategy() {
        log.info(
                "任务调度已启用 strategy={} loopIntervalMs={} leaderLockKey={} leaderLockMode=watchdog",
                schedulerProperties.getStrategy(),
                schedulerProperties.getLoopIntervalMs(),
                schedulerProperties.getLeaderLockKey());
    }

    @Scheduled(fixedDelayString = "${scheduler.loop-interval-ms:5000}")
    public void runScheduleLoop() {
        if (!schedulerLeaderElection.tryAcquireLeadership()) {
            log.debug("非 Leader 节点，跳过本轮调度 strategy={}", schedulerProperties.getStrategy());
            return;
        }
        try {
            schedulerService.scheduleLoop();
        } finally {
            schedulerLeaderElection.releaseLeadership();
        }
    }
}
