package com.imperium.distributed_lite_scheduler_v1.config;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 调度器调用远程 Worker 的 HTTP 客户端。
 */
@Configuration
public class WorkerRestClientConfig {

    public static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    @Bean
    public RestClient workerRestClient(TaskExecutorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getWorkerConnectTimeoutMs());
        factory.setReadTimeout(properties.getWorkerReadTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
    }
}
