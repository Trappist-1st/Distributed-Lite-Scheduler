package com.imperium.distributed_lite_scheduler_v1.config;

import com.imperium.distributed_lite_scheduler_v1.config.properties.SchedulerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {
}
