package com.imperium.distributed_lite_scheduler_v1.service.executor.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class ProcessRunningTaskHandle implements RunningTaskHandle {

    private final Long taskInstanceId;
    private final Process process;

    @Override
    public void cancelForcibly() {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("强制终止任务进程 taskInstanceId={}", taskInstanceId);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
