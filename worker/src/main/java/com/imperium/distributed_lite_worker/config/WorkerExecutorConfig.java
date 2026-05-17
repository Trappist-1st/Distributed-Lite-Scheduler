package com.imperium.distributed_lite_worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WorkerExecutorConfig {

    @Bean(name = "workerTaskExecutor")
    public Executor workerTaskExecutor(WorkerProperties properties) {
        WorkerProperties.Executor exec = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("worker-run-");
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
