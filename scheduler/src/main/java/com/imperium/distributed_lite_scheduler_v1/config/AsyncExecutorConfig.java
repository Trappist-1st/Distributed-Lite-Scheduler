package com.imperium.distributed_lite_scheduler_v1.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 启用 Spring {@code @Async}（Stream 消费由 {@link TaskCompletionRedisStreamConfig} 托管，无需单独线程池）。
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {
}
