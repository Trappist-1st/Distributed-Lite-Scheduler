package com.imperium.distributed_lite_worker;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class DistributedLiteWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributedLiteWorkerApplication.class, args);
    }
}
