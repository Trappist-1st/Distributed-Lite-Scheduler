package com.imperium.distributed_lite_worker.bootstrap;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.service.SchedulerNodeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerRegistrationRunner implements ApplicationRunner {

    private final WorkerProperties properties;
    private final SchedulerNodeClient schedulerNodeClient;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isRegistrationEnabled()) {
            log.info("Worker 自动注册已关闭");
            return;
        }
        schedulerNodeClient.register();
    }
}
