package com.imperium.distributed_lite_worker.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String WORKER_TOKEN = "workerToken";

    @Bean
    public OpenAPI workerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Lite Worker API")
                        .description("远程任务执行节点 — 接收调度中心下发的运行请求")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(
                                WORKER_TOKEN,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Worker-Token")
                                        .description("与 worker.api.token / task.executor.worker-api-token 一致")));
    }
}
