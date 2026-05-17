package com.imperium.distributed_lite_worker.bootstrap;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.service.SchedulerNodeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkerHeartbeatScheduler {

    private final WorkerProperties properties;
    private final SchedulerNodeClient schedulerNodeClient;

    @Scheduled(fixedDelayString = "${worker.heartbeat-interval-seconds:30}000")
    public void heartbeat() {
        if (!properties.isRegistrationEnabled()) {
            return;
        }
        schedulerNodeClient.heartbeat();
    }
}
