# 分布式工作流缓存一致性与并发控制完整解析

> 最后更新：2026-05-14
> 作者：工作流系统优化组
> 适用版本：Distributed Lite Scheduler V1

## 目录

1. [分布式缓存架构](#分布式缓存架构)
2. [实现细节](#实现细节)
3. [并发问题分析](#并发问题分析)
4. [解决方案](#解决方案)
5. [最佳实践](#最佳实践)
6. [故障排查](#故障排查)

---

## 分布式缓存架构

### 为什么需要分布式缓存？

在单机应用中，使用本地内存缓存（如 Caffeine）非常高效。但在分布式系统中，这会产生**缓存一致性问题**：

#### 问题场景

```
用户更新工作流 → API 实例 A 更新数据库 → 实例 A 清除本地缓存
           ↓
API 实例 B 读取执行计划 → 命中本地缓存（旧数据）
           ↓
Scheduler 实例读取执行计划 → 命中本地缓存（旧数据）
           ↓
使用过期的执行计划调度任务 ❌ 严重错误
```

### 两层缓存架构

```
┌─────────────────────────────────────────────────────────────┐
│                    工作流执行计划缓存                          │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  L1 本地缓存（Caffeine）         L2 分布式缓存（Redis）      │
│  ┌──────────────────────────┐   ┌──────────────────────┐   │
│  │ wf#123 → Plan v2         │   │ wf#123 → Plan v2    │   │
│  │ wf#456 → Plan v1         │───│ wf#456 → Plan v1    │   │
│  │ wf#789 → Plan v3 (本地只有) wf#789 → (Redis无)    │   │
│  │ TTL: 30分钟              │   │ TTL: 30分钟         │   │
│  │ 单实例                   │   │ 多实例共享          │   │
│  └──────────────────────────┘   └──────────────────────┘   │
│           ↑                              ↑                    │
│           │ 快速命中                    │ 跨实例一致性      │
│           └──────────────────────────────┘                    │
│                                                               │
│          Pub/Sub 失效通知机制                                │
│          ┌─────────────────────────────────────┐            │
│          │ Channel: workflow:execution:plan:   │            │
│          │ invalidate                          │            │
│          │ Message: wf#123 (工作流ID)          │            │
│          └─────────────────────────────────────┘            │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### 架构优势

| 特性 | 本地缓存(Caffeine) | 分布式缓存(Redis) | 两层组合 |
|------|---------|-----------|--------|
| 访问速度 | 非常快（内存） | 快（网络） | 优化 |
| 多实例共享 | ❌ | ✅ | ✅ |
| 缓存一致性 | ❌ | ✅ | ✅ |
| 内存占用 | 低 | 高 | 中等 |
| 网络开销 | 0 | 高 | 低（本地命中时） |

---

## 实现细节

### 1. DistributedWorkflowCacheManager

**职责**：
- 管理 L1 本地缓存和 L2 Redis 缓存的生命周期
- 实现多级缓存查询逻辑（本地 → Redis → 数据库）
- 处理缓存失效和跨实例通知

**核心方法**：

#### getExecutionPlan()

```java
public WorkflowExecutionPlan getExecutionPlan(
        Long workflowId, 
        Function<Long, WorkflowExecutionPlan> loader)
```

**查询流程**：

```
请求到达 (workflowId=123)
    ↓
[L1查询] localCache.getIfPresent(123)
    ├─ 命中 → 返回结果 ✓ (最快)
    └─ 未命中 ↓
    
[L2查询] redissonClient.getBucket(key).get()
    ├─ 命中 → 反序列化 → 回源到L1 → 返回结果 ✓ (快)
    └─ 未命中 ↓
    
[数据库查询] loader.apply(123)
    ├─ 失败/超时 → 抛异常
    └─ 成功 ↓
    
[双写入缓存]
    ├─ localCache.put(123, plan) 
    └─ redissonClient.putEx(key, plan, 30min)
    
返回结果 ✓ (最慢，但会缓存后续请求)
```

**代码示例**：

```java
// 业务方调用
WorkflowExecutionPlan plan = cacheManager.getExecutionPlan(
    workflowId,
    this::doBuildExecutionPlan  // 从数据库加载的 loader
);

// 内部流程
// 1. 本地命中 → 直接返回
// 2. Redis 命中 → 反序列化 + 回源本地 → 返回
// 3. 都未命中 → 调用 loader → 双写入缓存 → 返回
```

#### invalidateExecutionPlan()

```java
public void invalidateExecutionPlan(Long workflowId)
```

**失效流程**：

```
工作流更新完成
    ↓
[步骤1] 清除本实例 L1 本地缓存
    localCache.invalidate(workflowId)
    ↓
[步骤2] 清除 Redis L2 分布式缓存
    redissonClient.getBucket(key).delete()
    ↓
[步骤3] 发布 Pub/Sub 失效通知
    redissonClient.getTopic(channel).publish(workflowId)
    ↓
    其他实例接收通知 → 清除各自的 L1 缓存
```

**代码示例**：

```java
// 在 WorkflowServiceImpl.updateWorkflow() 中调用
workflowExecutionService.invalidatePlanCache(id);

// 触发流程：
// 1. 本实例：清 L1
// 2. Redis：清 L2
// 3. 其他实例通过 Pub/Sub 收到通知：清 L1
// 结果：所有实例的缓存立即失效
```

---

### 2. WorkflowCacheInvalidationListener

**职责**：
- 监听分布式缓存失效的 Pub/Sub 消息
- 当其他实例发布失效通知时，清除本实例的本地缓存

**工作流程**：

```java
@Component
@Slf4j
public class WorkflowCacheInvalidationListener {
    
    @PostConstruct
    public void subscribeToCacheInvalidation() {
        RTopic topic = redissonClient.getTopic(INVALIDATE_CHANNEL);
        
        // 注册监听器，接收 Long 类型消息（工作流 ID）
        topic.addListener(Long.class, (channel, workflowId) -> {
            // 收到其他实例的失效通知
            cacheManager.getLocalCache().invalidate(workflowId);
            log.info("跨实例缓存失效 workflowId={}", workflowId);
        });
    }
}
```

**监听机制**：

```
实例 A：发布失效通知
    ├─ 清 L1 本地缓存
    ├─ 清 Redis L2 缓存
    └─ 发布 Pub/Sub 消息

    ↓↓↓ 消息传播 ↓↓↓

实例 B：接收通知
    ├─ handleCacheInvalidationMessage(workflowId)
    └─ 清 L1 本地缓存（L2 已由 A 清除）

实例 C：接收通知
    ├─ handleCacheInvalidationMessage(workflowId)
    └─ 清 L1 本地缓存（L2 已由 A 清除）
```

---

### 3. WorkflowExecutionServiceImpl 集成

```java
@Service
@Slf4j
public class WorkflowExecutionServiceImpl implements WorkflowExecutionService {
    
    @Autowired
    private DistributedWorkflowCacheManager cacheManager;
    
    @Override
    public WorkflowExecutionPlan buildExecutionPlan(Long workflowId) {
        // 委托给分布式缓存管理器
        return cacheManager.getExecutionPlan(workflowId, this::doBuildExecutionPlan);
    }
    
    @Override
    public void invalidatePlanCache(Long workflowId) {
        // 委托给分布式缓存管理器，实现跨实例失效
        cacheManager.invalidateExecutionPlan(workflowId);
    }
}
```

---

## 并发问题分析

### 问题 1：多实例同时更新同一工作流

#### 场景模拟

```
时间轴：
T1   用户A 从实例1 发起更新工作流123
T2   用户B 从实例2 发起更新工作流123
     ↓
T3   实例1 和 实例2 同时读取数据库的工作流123
T4   实例1 修改字段A，实例2 修改字段B
T5   实例1 写回数据库（字段A已修改）
T6   实例2 写回数据库（字段A被覆盖为未修改状态）❌
     ↓
结果：用户A的修改丢失（Lost Update）
```

#### 原因分析

```
实例1读取         实例2读取
 ↓                  ↓
┌──────────────────┐
│ wf#123 v0        │
│ {                │
│   name: "old"    │ ← 都读到相同版本
│   status: 1      │
│   desc: null     │
│ }                │
└──────────────────┘
 ↓ 修改           ↓ 修改
实例1修改name    实例2修改status
 ↓                ↓
 {                {
   name: "new",    name: "old",
   status: 1,      status: 0,     ← 实例1的修改被覆盖
   desc: null      desc: null
 }                }
 ↓ 写回           ↓ 写回
 T5              T6（晚于T5）
        先写    后写，覆盖先写
      结果：name 回到 "old"，status 变成 0
      ❌ 实例1 的 name 修改丢失
```

### 问题 2：缓存与数据库不一致（ABA问题）

#### 场景模拟

```
T1  实例A 读缓存 wf#123 v2（30分钟内有效）
    ↓
T2  用户在实例B 更新工作流123 → DAG 从v2改为v3
    ├─ 更新数据库
    ├─ 清 Redis 和发 Pub/Sub
    └─ 实例A 收到通知，清 L1
    ↓
T3  实例A 缓存未命中，重新查数据库 → 拿到v3 ✓
    ↓
但假如 Redis 故障或网络延迟：
T1  实例A 读缓存 wf#123 v2
T2  用户在实例B 更新 → 数据库变 v3，Redis 清除
T3  网络延迟 → Pub/Sub 消息迟到 2 秒
    └─ 实例A 仍在用 v2（还在 30 分钟 TTL 内）
T4  调度器用 v2 执行任务 ❌ 使用过期版本
T5  Pub/Sub 消息到达（太晚了）
```

### 问题 3：并发缓存失效时的竞态条件

#### 场景模拟

```
实例A              Redis           实例B
    清 L1 ──┐
           │
           ├→ 清缓存  ←── 实例B查询 (竞态)
           │
           ├→ 发Pub/Sub
           │
    查询DB     查询Redis
      ↓         ↓ 命中
    v3         v2 ❌
    
结果：实例B仍用旧数据，因为清缓存和查询顺序竞争
```

---

## 解决方案

### 1. 使用数据库版本号（Version Optimistic Lock）

在 Workflow 表中添加 version 字段，通过乐观锁防止更新冲突：

```sql
ALTER TABLE workflow ADD COLUMN version INT DEFAULT 0;

UPDATE workflow 
SET dagJson = '...', version = version + 1
WHERE id = 123 AND version = 0;
-- 如果没有行受影响，说明版本已改变，需要重试
```

**在 WorkflowServiceImpl.updateWorkflow() 中集成**：

```java
@Override
@Transactional
public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
    Workflow workflow = requireOwnedWorkflow(id);
    
    // 保存原始版本号
    Integer originalVersion = workflow.getVersion();
    
    // 修改工作流
    updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
    updateDagJson(workflow, request.getDagJson());
    
    // 使用版本号进行乐观锁更新
    int updated = workflowMapper.updateByIdWithVersion(
        workflow.getId(),
        workflow,
        originalVersion
    );
    
    if (updated == 0) {
        throw new IllegalStateException(
            "工作流已被其他用户修改，请刷新后重试"
        );
    }
    
    // 版本号递增（由数据库自动处理或应用层处理）
    // 清除缓存
    workflowExecutionService.invalidatePlanCache(id);
}
```

**MyBatis Plus 实现**：

```java
@Repository
public interface WorkflowMapper extends BaseMapper<Workflow> {
    
    /**
     * 使用版本号进行乐观锁更新
     * @return 更新行数（0 表示版本冲突，>0 表示成功）
     */
    @Update("""
        UPDATE workflow 
        SET 
            workflow_name = #{workflow.workflowName},
            dag_json = #{workflow.dagJson},
            description = #{workflow.description},
            schedule_type = #{workflow.scheduleType},
            cron_expression = #{workflow.cronExpression},
            timeout_seconds = #{workflow.timeoutSeconds},
            alert_on_failure = #{workflow.alertOnFailure},
            status = #{workflow.status},
            version = version + 1,
            updated_at = NOW()
        WHERE 
            id = #{workflowId} 
            AND version = #{expectedVersion}
    """)
    int updateByIdWithVersion(
        @Param("workflowId") Long workflowId,
        @Param("workflow") Workflow workflow,
        @Param("expectedVersion") Integer expectedVersion
    );
}
```

### 2. 使用分布式锁防止并发修改

通过 Redis 分布式锁，同时只允许一个实例修改同一工作流：

```java
@Override
@Transactional
public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
    // 获取分布式锁（工作流级别）
    RLock lock = redissonClient.getLock("workflow:update:" + id);
    
    try {
        // 尝试获取锁，最多等待 5 秒，持有时间 30 秒
        if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "工作流正在被其他用户修改，请稍后重试"
            );
        }
        
        // 再次读取，确保获取最新数据（防止中间被修改）
        Workflow workflow = requireOwnedWorkflow(id);
        
        // 执行修改
        updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
        updateDagJson(workflow, request.getDagJson());
        
        int updated = workflowMapper.updateById(workflow);
        if (updated != 1) {
            throw new IllegalStateException("更新工作流失败");
        }
        
        // 修改成功，清除缓存
        workflowExecutionService.invalidatePlanCache(id);
        
        log.info("工作流更新成功 id={}", id);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("获取锁被中断", e);
    } finally {
        // 自动释放锁
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**架构图**：

```
并发修改请求                    
    ├─ 实例A: 修改工作流123
    ├─ 实例B: 修改工作流123  
    └─ 实例C: 修改工作流123
         ↓
    Redis分布式锁：workflow:update:123
         ↓
    实例A 获得锁 ──→ 修改 → 清缓存 → 释放锁
         ↓ 已释放
    实例B 获得锁 ──→ 修改 → 清缓存 → 释放锁
         ↓ 已释放
    实例C 获得锁 ──→ 修改 → 清缓存 → 释放锁
    
结果：串行化执行，消除并发冲突
```

### 3. 使用缓存预热和主动失效

```java
/**
 * 工作流更新事件处理
 * 在数据变更后主动失效缓存，而不是等待 TTL 过期
 */
@EventListener
public void onWorkflowUpdated(WorkflowUpdatedEvent event) {
    Long workflowId = event.getWorkflowId();
    
    // 立即失效缓存（L1 + L2 + 通知其他实例）
    workflowExecutionService.invalidatePlanCache(workflowId);
    
    log.info("工作流更新事件处理：缓存已失效 workflowId={}", workflowId);
}

/**
 * 缓存预热：启动时加载常用工作流的执行计划
 */
@PostConstruct
public void warmupCache() {
    List<Workflow> frequentWorkflows = workflowMapper.selectFrequentWorkflows(10);
    for (Workflow wf : frequentWorkflows) {
        try {
            WorkflowExecutionPlan plan = workflowExecutionService.buildExecutionPlan(wf.getId());
            log.info("缓存预热成功 workflowId={}", wf.getId());
        } catch (Exception e) {
            log.warn("缓存预热失败 workflowId={}", wf.getId(), e);
        }
    }
}
```

---

## 最佳实践

### 1. 缓存键设计规范

```java
// ✓ 好：清晰的前缀分层
"workflow:execution:plan:123"
"workflow:execution:plan:456"

// ✗ 避免：简单数字键，容易冲突
"123"
"456"

// ✓ 好：包含版本或时间戳（可选）
"workflow:execution:plan:123:v2"
"workflow:execution:plan:123:generated_at_1715710800"
```

### 2. 缓存 TTL 设置原则

```java
// 执行计划（30分钟）
// 原因：工作流定义不常变动，但要及时生效
.expireAfterWrite(30, TimeUnit.MINUTES)

// 任务执行状态（5分钟）
// 原因：执行中频繁变化，但数据库查询成本高
.expireAfterWrite(5, TimeUnit.MINUTES)

// 用户权限（1小时）
// 原因：权限变化不频繁，但要一定的延迟容忍度
.expireAfterWrite(1, TimeUnit.HOURS)
```

### 3. 缓存预算管理

```java
// L1 本地缓存
.maximumSize(500)              // 500 个工作流
.weigher((k, v) -> {           // 按对象大小限额
    return v.getLayers().size() * 100;  // 粗略估算
})
.maximumWeight(10 * 1024 * 1024)  // 最多 10MB

// Redis L2 缓存
// 配置 Redis maxmemory 和驱逐策略
maxmemory 2gb
maxmemory-policy allkeys-lru   // LRU 驱逐策略
```

### 4. 监控和告警

```java
/**
 * 定期上报缓存统计
 */
@Scheduled(fixedDelay = 60000)  // 每分钟
public void reportCacheMetrics() {
    String stats = workflowExecutionService.getCacheStats();
    // 格式：CacheStats{hitCount=1000, missCount=50, ...}
    
    log.info("缓存指标 {}", stats);
    
    // 上报到监控系统（如 Prometheus）
    metricsRegistry.gauge("workflow.cache.hit.rate", () -> {
        // 计算命中率
    });
}

/**
 * 告警规则
 */
// 缓存命中率 < 50% → 告警
// Redis 连接失败 → 告警
// 缓存失效通知消息堆积 → 告警
```

---

## 故障排查

### 问题 1：缓存不生效，每次都查数据库

**排查步骤**：

```java
// 1. 检查缓存管理器是否注入成功
@Autowired
private DistributedWorkflowCacheManager cacheManager;
// 使用 IDE 的"Find Usages"确保注入

// 2. 检查本地缓存是否有数据
String stats = cacheManager.getCacheStats();
log.info("缓存统计: {}", stats);
// 如果 hitCount=0，说明没有命中

// 3. 检查 Redis 连接
try {
    redissonClient.getBucket("test").set("value");
    log.info("Redis 连接正常");
} catch (Exception e) {
    log.error("Redis 连接失败", e);
}

// 4. 检查缓存写入是否成功
cacheManager.getExecutionPlan(123, id -> {
    log.info("触发 loader，从数据库加载");
    return workflowExecutionService.doBuildExecutionPlan(id);
});
```

### 问题 2：其他实例没有收到缓存失效通知

**排查步骤**：

```java
// 1. 检查 Pub/Sub 监听器是否启动
// 在 WorkflowCacheInvalidationListener 的 subscribeToCacheInvalidation 
// 方法中添加日志

@PostConstruct
public void subscribeToCacheInvalidation() {
    try {
        RTopic topic = redissonClient.getTopic(INVALIDATE_CHANNEL);
        topic.addListener(Long.class, (channel, workflowId) -> {
            log.info("✓ 收到通知 channel={} workflowId={}", channel, workflowId);
            handleCacheInvalidationMessage(workflowId);
        });
        log.info("✓ 监听器已启动");
    } catch (Exception e) {
        log.error("✗ 监听器启动失败", e);
    }
}

// 2. 检查消息是否被发布
cacheManager.invalidateExecutionPlan(123);
// 查看日志，确保有"已发布缓存失效通知"

// 3. 检查 Redis Pub/Sub 是否工作
redissonClient.getTopic("test:topic").addListener(String.class, (channel, msg) -> {
    log.info("收到消息: {}", msg);
});
redissonClient.getTopic("test:topic").publish("hello");
```

### 问题 3：并发更新导致数据不一致

**排查步骤**：

```java
// 1. 检查是否启用了版本号乐观锁
SELECT * FROM workflow WHERE id = 123;
// 查看 version 字段是否存在且递增

// 2. 检查更新是否使用了版本号
// 查看 WorkflowServiceImpl.updateWorkflow() 中的 SQL

// 3. 测试并发场景
ExecutorService executor = Executors.newFixedThreadPool(3);
for (int i = 0; i < 3; i++) {
    executor.submit(() -> {
        try {
            workflowService.updateWorkflow(123, new WorkflowUpdateRequest());
        } catch (IllegalStateException e) {
            log.warn("版本冲突: {}", e.getMessage());
        }
    });
}
executor.awaitTermination(10, TimeUnit.SECONDS);
```

---

## 总结

| 问题 | 原因 | 解决方案 | 优先级 |
|------|------|--------|-------|
| 多实例缓存不一致 | Caffeine 本地隔离 | 两层缓存 + Pub/Sub | 🔴 高 |
| 并发修改冲突 | 无锁制机制 | 版本号乐观锁 或 分布式锁 | 🔴 高 |
| 缓存与数据库不一致 | TTL 导致过期数据 | 主动失效 + 版本号 | 🟡 中 |
| 缓存命中率低 | 失效过于频繁 | 调整 TTL + 缓存预热 | 🟡 中 |
| 性能下降 | 缓存策略不当 | 分层缓存 + 预热 | 🟢 低 |

---

## 参考资源

- [Redisson 官方文档](https://redisson.org/)
- [Caffeine 官方文档](https://github.com/ben-manes/caffeine)
- [分布式系统一致性](https://en.wikipedia.org/wiki/Consistency_(database_systems))
- [乐观锁 vs 悲观锁](https://www.baeldung.com/jpa-optimistic-locking)
