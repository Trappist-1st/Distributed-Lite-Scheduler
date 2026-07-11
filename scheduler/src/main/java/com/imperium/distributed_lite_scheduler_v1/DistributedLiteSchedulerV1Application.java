package com.imperium.distributed_lite_scheduler_v1;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.imperium.distributed_lite_scheduler_v1.mapper")
public class DistributedLiteSchedulerV1Application {

    public static void main(String[] args) {
        SpringApplication.run(DistributedLiteSchedulerV1Application.class, args);
    }

}
