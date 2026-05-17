package com.imperium.distributed_lite_worker.config;

import com.imperium.distributed_lite_worker.security.WorkerTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class WorkerSecurityConfig {

    @Bean
    public WorkerTokenFilter workerTokenFilter(WorkerProperties properties) {
        return new WorkerTokenFilter(properties);
    }

    @Bean
    public SecurityFilterChain workerSecurityFilterChain(
            HttpSecurity http, WorkerTokenFilter workerTokenFilter, WorkerProperties properties)
            throws Exception {
        boolean tokenRequired = StringUtils.hasText(properties.getApi().getToken());
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/error", "/actuator/health").permitAll();
                    if (tokenRequired) {
                        auth.anyRequest().authenticated();
                    } else {
                        auth.anyRequest().permitAll();
                    }
                });
        if (tokenRequired) {
            http.addFilterBefore(workerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
