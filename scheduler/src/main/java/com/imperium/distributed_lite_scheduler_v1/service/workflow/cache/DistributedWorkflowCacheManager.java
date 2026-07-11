package com.imperium.distributed_lite_scheduler_v1.service.workflow.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 分布式工作流执行计划缓存管理器
 * 
 * 实现两层缓存架构：
 * - L1 本地缓存（Caffeine）：单实例快速访问，30分钟过期
 * - L2 分布式缓存（Redis）：多实例共享，用于跨实例协调和失效通知
 * 
 * 缓存一致性保证：
 * 1. 本地缓存失效时，同时清除 Redis 和发布 Pub/Sub 失效通知
 * 2. 其他实例接收失效通知后，清除各自的本地缓存
 * 3. 读取时优先本地缓存，未命中则查 Redis，都未命中则从数据库加载
 * 
 * @author UNSC
 * @since 2026-05-14
 */
@Component
@Slf4j
public class DistributedWorkflowCacheManager {
    
    /**
     * Redis 缓存 key 前缀
     */
    private static final String CACHE_KEY_PREFIX = "workflow:execution:plan:";
    
    /**
     * 缓存失效通知的 Pub/Sub channel
     */
    private static final String INVALIDATE_CHANNEL = "workflow:execution:plan:invalidate";
    
    /**
     * Redis 中缓存的 TTL（30分钟）
     */
    private static final long REDIS_TTL_SECONDS = 1800;
    
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * L1 本地缓存：Caffeine
     * 配置参数：
     * - 最大容量：500 个工作流
     * - 写入后过期：30 分钟
     * - 统计信息：记录缓存命中率等
     * -- GETTER --
     *  获取本地缓存对象本身（供监听器和外部使用）
     *
     * @return Caffeine 本地缓存对象

     */
    @Getter
    private final Cache<Long, WorkflowExecutionPlan> localCache =
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .recordStats()
            .build();
    
    /**
     * 从分布式缓存读取执行计划
     * 
     * 流程：
     * 1. 先查 L1 本地缓存（Caffeine）- 最快
     * 2. 未命中则查 L2 Redis - 快速，跨实例共享
     * 3. 都未命中则调用 loader 从数据库加载，并双写入缓存
     * 
     * @param workflowId 工作流 ID
     * @param loader 数据库加载函数（从工作流 ID 返回执行计划）
     * @return 执行计划对象
     */
    public WorkflowExecutionPlan getExecutionPlan(
            Long workflowId,
            Function<Long, WorkflowExecutionPlan> loader) {

        if (workflowId == null) {
            throw new IllegalArgumentException("工作流 ID 不能为空");
        }

        // 使用 Caffeine 的 get(key, mappingFunction) 而非 getIfPresent()。
        // 区别：同一个 key 并发时只有一个线程执行 mappingFunction，其他线程等待并复用结果，
        // 消灭进程内缓存击穿（原先 1000 个并发全部穿透到下游）。
        return localCache.get(workflowId, id -> loadFromRedisOrDb(id, loader));
    }

    /**
     * L1 miss 后的加载链：L2 Redis → DB，加载成功后双写回 L1+L2。
     * 此方法在 Caffeine per-key 锁内执行，单 JVM 内不会并发。
     * 跨 JVM 的并发穿透由 Redis 的最终一致性承担（多次 DB 加载结果相同，可接受）。
     */
    private WorkflowExecutionPlan loadFromRedisOrDb(Long workflowId, Function<Long, WorkflowExecutionPlan> loader) {
        // 第一步：尝试从 L2 Redis 获取
        String redisKey = CACHE_KEY_PREFIX + workflowId;
        try {
            RBucket<Object> bucket = redissonClient.getBucket(redisKey);
            Object cached = bucket.get();
            if (cached != null) {
                try {
                    WorkflowExecutionPlan plan = objectMapper.readValue(
                            objectMapper.writeValueAsString(cached),
                            WorkflowExecutionPlan.class);
                    log.debug("缓存命中：L2 Redis缓存 workflowId={}", workflowId);
                    return plan;
                } catch (Exception e) {
                    log.warn("反序列化 Redis 缓存失败，继续查询数据库 workflowId={}", workflowId, e);
                }
            }
        } catch (Exception e) {
            log.warn("访问 Redis 缓存异常，继续查询数据库 workflowId={}", workflowId, e);
        }

        // 第二步：缓存未命中，从数据库加载
        log.debug("缓存未命中，从数据库加载 workflowId={}", workflowId);
        WorkflowExecutionPlan plan = loader.apply(workflowId);

        // 第三步：回填 L2 Redis（L1 由 Caffeine.get() 在返回后自动填充）
        if (plan != null) {
            putToRedis(workflowId, plan);
            log.info("数据库加载并写入 L2 缓存成功 workflowId={}", workflowId);
        }

        return plan;
    }
    
    /**
     * 写入 Redis 缓存
     * 
     * @param workflowId 工作流 ID
     * @param plan 执行计划对象
     */
    private void putToRedis(Long workflowId, WorkflowExecutionPlan plan) {
        if (plan == null) {
            return;
        }
        
        try {
            String redisKey = CACHE_KEY_PREFIX + workflowId;
            String json = objectMapper.writeValueAsString(plan);
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            // Redisson RBucket：使用新的 set 方法（替代废弃的方法）
            bucket.set(json, java.time.Duration.ofSeconds(REDIS_TTL_SECONDS));
            log.debug("写入 Redis 缓存成功 workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("写入 Redis 缓存失败，但不影响业务流程 workflowId={}", workflowId, e);
            // Redis 写入失败不影响业务，继续执行
        }
    }
    
    /**
     * 分布式缓存失效（跨实例协调）
     * 
     * 执行步骤：
     * 1. 清除本实例的 L1 本地缓存
     * 2. 清除 Redis L2 分布式缓存
     * 3. 发布 Pub/Sub 失效通知，其他实例接收后清除各自的 L1 缓存
     * 
     * 这样保证了在分布式环境中，所有实例的缓存都能及时失效。
     * 
     * @param workflowId 要失效的工作流 ID
     */
    public void invalidateExecutionPlan(Long workflowId) {
        if (workflowId == null) {
            return;
        }
        
        log.info("开始分布式缓存失效 workflowId={}", workflowId);
        
        try {
            // 步骤1：清除本实例的 L1 本地缓存
            localCache.invalidate(workflowId);
            log.debug("已清除 L1 本地缓存 workflowId={}", workflowId);
            
            // 步骤2：清除 Redis L2 分布式缓存
            String redisKey = CACHE_KEY_PREFIX + workflowId;
            redissonClient.getBucket(redisKey).delete();
            log.debug("已清除 L2 Redis 缓存 workflowId={}", workflowId);
            
            // 步骤3：发布失效通知（Pub/Sub）
            // 其他实例监听此 channel，收到消息后清除各自的 L1 缓存
            RTopic topic = redissonClient.getTopic(INVALIDATE_CHANNEL);
            topic.publish(workflowId);
            log.info("已发布缓存失效通知 workflowId={}", workflowId);
            
        } catch (Exception e) {
            log.error("分布式缓存失效过程中出现异常，但不影响业务继续执行 workflowId={}", workflowId, e);
            // 缓存失效异常不影响业务流程
        }
    }
    
    /**
     * 获取本地缓存统计信息（用于监控和调试）
     * 
     * @return 缓存统计信息字符串，包含：
     *         - 缓存命中数
     *         - 缓存未命中数
     *         - 命中率百分比
     */
    public String getCacheStats() {
        return String.format(
            "WorkflowExecutionPlan缓存统计 - %s",
            localCache.stats().toString()
        );
    }

    /**
     * 获取本地缓存中指定工作流的执行计划（仅用于测试和调试）
     * 
     * @param workflowId 工作流 ID
     * @return 缓存中的执行计划，如果未缓存则返回 null
     */
    public WorkflowExecutionPlan getFromLocalCache(Long workflowId) {
        return localCache.getIfPresent(workflowId);
    }
    
    /**
     * 清空所有本地缓存（危险操作，仅用于测试）
     */
    public void clearLocalCache() {
        localCache.invalidateAll();
        log.warn("已清空所有本地缓存（仅用于测试）");
    }
}
