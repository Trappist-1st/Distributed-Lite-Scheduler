package com.imperium.distributed_lite_scheduler_v1.config;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务执行线程池（进程内 Worker，Step1）。
 */
@Configuration
@EnableConfigurationProperties(TaskExecutorProperties.class)
public class TaskExecutorConfig {

    @Bean(name = "taskExecutorThreadPool")
    public Executor taskExecutorThreadPool(TaskExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("task-exec-");
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
