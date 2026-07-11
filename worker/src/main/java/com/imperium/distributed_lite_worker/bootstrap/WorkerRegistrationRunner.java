package com.imperium.distributed_lite_worker.bootstrap;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.service.SchedulerNodeClient;
import jakarta.annotation.PreDestroy;
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

    /**
     * JVM 关闭钩子：通知调度中心将本节点标记 OFFLINE，使调度器立即停止向本节点分发任务。
     * 线程池的 awaitTerminationSeconds（30s）确保正在执行的任务有机会完成后再退出。
     */
    @PreDestroy
    public void onShutdown() {
        if (!properties.isRegistrationEnabled()) {
            return;
        }
        log.info("Worker 正在优雅停机，通知调度中心下线...");
        try {
            schedulerNodeClient.deregister();
        } catch (Exception e) {
            log.warn("下线通知失败（调度中心不可达），依赖 NodeHeartbeatWatchdog 兜底", e);
        }
    }
}
