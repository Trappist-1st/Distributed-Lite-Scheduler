package com.imperium.distributed_lite_scheduler_v1.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger UI 全局配置。
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    public static final String INTERNAL_TOKEN = "internalToken";

    @Bean
    public OpenAPI schedulerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Lite Scheduler API")
                        .description("分布式轻量调度平台 — 调度中心 REST API（认证、租户、任务、资源、工作流）")
                        .version("v1")
                        .contact(new Contact().name("Imperium").email("support@example.com"))
                        .license(new License().name("Apache 2.0")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("登录或切换租户后获得的 JWT，请求头：Authorization: Bearer {token}"))
                        .addSecuritySchemes(
                                INTERNAL_TOKEN,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Internal-Token")
                                        .description("Worker 回调等内部接口令牌，与配置 internal.api.token 一致")));
    }
}
