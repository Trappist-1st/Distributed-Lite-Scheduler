package com.imperium.distributed_lite_scheduler_v1.service.workflow.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 工作流缓存失效通知监听器
 * 
 * 功能：
 * - 监听分布式缓存失效的 Pub/Sub 消息
 * - 当其他实例发起缓存失效时，本实例接收通知并清除本地缓存
 * - 确保分布式环境中所有实例的缓存保持一致
 * 
 * 工作原理：
 * 1. 启动时注册 Pub/Sub 监听器
 * 2. 监听 "workflow:execution:plan:invalidate" channel
 * 3. 收到失效通知（工作流 ID）后，清除本实例的 L1 本地缓存
 * 4. L2 Redis 缓存由发起失效的实例已清除
 * 
 * @author UNSC
 * @since 2026-05-14
 */
@Component
@Slf4j
public class WorkflowCacheInvalidationListener {
    
    /**
     * 缓存失效通知的 Pub/Sub channel（与 CacheManager 保持一致）
     */
    private static final String INVALIDATE_CHANNEL = "workflow:execution:plan:invalidate";
    
    @Autowired
    private DistributedWorkflowCacheManager cacheManager;
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 在 Spring 容器启动完成后注册 Pub/Sub 监听器
     * 
     * 这样确保：
     * 1. RedissonClient 已初始化
     * 2. cacheManager 已注入
     * 3. 监听器在应用启动时立即激活
     */
    @PostConstruct
    public void subscribeToCacheInvalidation() {
        try {
            RTopic topic = redissonClient.getTopic(INVALIDATE_CHANNEL);
            
            // 注册监听器：接收 Long 类型的消息（工作流 ID）
            topic.addListener(Long.class, (channel, workflowId) -> {
                log.info("收到跨实例缓存失效通知 channel={} workflowId={}", channel, workflowId);
                handleCacheInvalidationMessage(workflowId);
            });
            
            log.info("工作流缓存失效通知监听器已启动 channel={}", INVALIDATE_CHANNEL);
        } catch (Exception e) {
            log.error("启动缓存失效通知监听器失败", e);
            throw new RuntimeException("缓存失效通知监听器初始化失败", e);
        }
    }
    
    /**
     * 处理缓存失效通知消息
     * 
     * 当其他实例发布缓存失效通知时，本实例接收到工作流 ID，
     * 然后清除本地缓存中对应的执行计划。
     * 
     * 注意：
     * - Redis L2 缓存已由发起失效的实例清除，无需再清
     * - 只需清除本实例的 L1 本地缓存（Caffeine）
     * 
     * @param workflowId 要失效的工作流 ID
     */
    private void handleCacheInvalidationMessage(Long workflowId) {
        if (workflowId == null) {
            log.warn("接收到 null 的工作流 ID，忽略处理");
            return;
        }
        
        try {
            // 从本实例的 L1 本地缓存中移除对应的执行计划
            // Redis L2 缓存由发起实例已清除，无需处理
            cacheManager.getLocalCache().invalidate(workflowId);
            log.info("已清除本实例的本地缓存 workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("处理缓存失效通知时出现异常 workflowId={}", workflowId, e);
            // 即使处理失败也不中断，缓存会在 30 分钟后自动过期
        }
    }
}
