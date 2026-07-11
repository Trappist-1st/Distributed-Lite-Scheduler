package com.imperium.distributed_lite_scheduler_v1.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 工作流执行器线程池配置
 * 
 * 用于工作流任务的并行执行
 * 
 * 配置说明：
 * - 核心线程数：根据CPU核心数调整
 * - 最大线程数：根据系统负载调整
 * - 队列容量：避免OOM
 * - 拒绝策略：CallerRunsPolicy，避免任务丢失
 */
@Configuration
@Slf4j
public class WorkflowExecutorConfig {
    
    /**
     * 创建工作流执行器线程池
     * 
     * 线程池参数建议：
     * - corePoolSize: CPU核心数 * 2
     * - maximumPoolSize: CPU核心数 * 4
     * - keepAliveTime: 60秒
     * - workQueue: LinkedBlockingQueue(1000)
     * - rejectedExecutionHandler: CallerRunsPolicy
     * 
     * TODO: 根据实际业务场景调整参数
     */
    @Bean(name = "workflowExecutorThreadPool")
    public ExecutorService workflowExecutorThreadPool() {
        int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        int maximumPoolSize = Runtime.getRuntime().availableProcessors() * 4;
        long keepAliveTime = 60L;
        
        log.info("初始化工作流执行器线程池, corePoolSize={}, maximumPoolSize={}", 
                corePoolSize, maximumPoolSize);
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 允许核心线程超时
        executor.allowCoreThreadTimeOut(true);
        
        return executor;
    }
    
}
