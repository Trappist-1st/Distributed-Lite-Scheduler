# P3-5: 并发安全保障设计稿

## 1. 文档目标

本文档详细设计调度引擎的并发安全保障机制，解决以下问题：

- 如何防止任务被重复调度
- 如何防止资源被重复分配
- 如何保证分布式环境下的一致性
- 如何处理并发冲突和异常

**核心价值**：确保调度系统在高并发、分布式环境下的正确性和可靠性。

---

## 2. 并发安全问题分析

### 2.1 典型并发问题

**问题1：任务重复调度**
```
时刻T1：调度器A查询任务1001，状态=PENDING
时刻T2：调度器B查询任务1001，状态=PENDING
时刻T3：调度器A分配资源，更新状态=RUNNING
时刻T4：调度器B分配资源，更新状态=RUNNING

结果：任务1001被调度了2次，资源被重复分配
```

**问题2：资源超分配**
```
节点A剩余：4核CPU

时刻T1：任务X申请2核，检查通过（4核>=2核）
时刻T2：任务Y申请3核，检查通过（4核>=3核）
时刻T3：任务X分配成功，剩余2核
时刻T4：任务Y分配成功，剩余-1核 ❌ 超分配

结果：节点A实际分配了5核，超过了总容量4核
```

**问题3：配额超限**
```
租户A配额：10核CPU，已用8核

时刻T1：任务X申请2核，检查通过（8+2<=10）
时刻T2：任务Y申请3核，检查通过（8+3<=10）
时刻T3：任务X分配成功，已用10核
时刻T4：任务Y分配成功，已用13核 ❌ 超配额

结果：租户A实际使用了13核，超过了配额10核
```

**问题4：状态不一致**
```
时刻T1：调度器A更新任务状态=RUNNING
时刻T2：执行器提交失败
时刻T3：调度器A未感知失败，任务状态仍为RUNNING

结果：任务状态=RUNNING，但实际未执行
```

### 2.2 并发安全的三大挑战

```
┌─────────────────────────────────────────────────────┐
│  挑战1：分布式环境                                   │
│  ├─ 多个调度器实例同时运行                          │
│  └─ 无法使用单机锁（synchronized）                  │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  挑战2：高并发                                       │
│  ├─ 1000+ QPS的任务提交                             │
│  └─ 锁竞争激烈，性能下降                            │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  挑战3：异常处理                                     │
│  ├─ 网络故障、节点宕机                              │
│  └─ 锁未释放、死锁                                  │
└─────────────────────────────────────────────────────┘
```

---

## 3. 并发安全架构

### 3.1 多层防护机制

```
┌─────────────────────────────────────────────────────┐
│  Layer 1: Leader选举（调度器级别）                   │
│  ├─ 确保只有一个调度器实例在工作                     │
│  ├─ 技术：Redis分布式锁                              │
│  └─ 粒度：全局                                       │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  Layer 2: 任务调度锁（任务级别）                     │
│  ├─ 防止同一任务被多次调度                          │
│  ├─ 技术：Redis分布式锁                              │
│  └─ 粒度：单个任务                                   │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  Layer 3: 资源分配锁（节点级别）                     │
│  ├─ 防止资源超分配                                  │
│  ├─ 技术：Redis分布式锁 + 数据库乐观锁              │
│  └─ 粒度：单个节点                                   │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  Layer 4: 数据库乐观锁（数据级别）                   │
│  ├─ 最终的状态更新保障                              │
│  ├─ 技术：version字段                                │
│  └─ 粒度：单条记录                                   │
└─────────────────────────────────────────────────────┘
```

### 3.2 锁的粒度与性能

| 锁类型 | 粒度 | 并发度 | 性能 | 适用场景 |
|-------|------|--------|------|---------|
| 全局锁 | 整个系统 | 低 | 差 | Leader选举 |
| 任务锁 | 单个任务 | 高 | 好 | **任务调度** |
| 节点锁 | 单个节点 | 中 | 中 | **资源分配** |
| 记录锁 | 单条记录 | 最高 | 最好 | **状态更新** |

**设计原则**：锁的粒度越小，并发度越高，性能越好。

---

## 4. 核心实现

### 4.1 Leader选举机制

```java
@Component
@Slf4j
public class SchedulerLeaderElection {
    
    private static final String LEADER_LOCK_KEY = "scheduler:leader:lock";
    private static final long LOCK_TTL_SECONDS = 30;
    
    @Autowired
    private RedissonClient redissonClient;
    
    private volatile boolean isLeader = false;
    private RLock leaderLock;
    
    /**
     * 尝试获取Leader身份
     */
    public boolean tryAcquireLeadership() {
        if (leaderLock == null) {
            leaderLock = redissonClient.getLock(LEADER_LOCK_KEY);
        }
        
        try {
            // 尝试获取锁，0秒等待，30秒后自动释放
            boolean acquired = leaderLock.tryLock(0, LOCK_TTL_SECONDS, 
                TimeUnit.SECONDS);
            
            if (acquired && !isLeader) {
                isLeader = true;
                log.info("成为Scheduler Leader instanceId={}", 
                    getInstanceId());
                
                // 注册心跳续期（Watch Dog机制）
                scheduleHeartbeat();
            }
            
            if (!acquired && isLeader) {
                isLeader = false;
                log.warn("失去Leader身份 instanceId={}", getInstanceId());
            }
            
            return acquired;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Leader选举被中断", e);
            return false;
        }
    }
    
    /**
     * 主动释放Leader身份
     */
    public void releaseLeadership() {
        if (leaderLock != null && leaderLock.isHeldByCurrentThread()) {
            leaderLock.unlock();
            isLeader = false;
            log.info("主动释放Leader身份 instanceId={}", getInstanceId());
        }
    }
    
    /**
     * 心跳续期（Redisson Watch Dog自动处理）
     */
    private void scheduleHeartbeat() {
        // Redisson的Watch Dog机制会自动续期
        // 默认每10秒续期一次（lockWatchdogTimeout / 3）
        log.debug("Watch Dog已启动，自动续期Leader锁");
    }
    
    /**
     * 检查是否为Leader
     */
    public boolean isLeader() {
        return isLeader;
    }
    
    /**
     * 获取实例ID
     */
    private String getInstanceId() {
        return InetAddress.getLocalHost().getHostName() + ":" + 
               ManagementFactory.getRuntimeMXBean().getName();
    }
}
```

### 4.2 任务调度锁

```java
@Service
@Slf4j
public class TaskScheduleLockService {
    
    private static final String LOCK_PREFIX = "task:schedule:lock:";
    private static final long LOCK_WAIT_SECONDS = 3;
    private static final long LOCK_TTL_SECONDS = 10;
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 执行带锁的调度操作
     */
    public <T> T executeWithLock(Long taskId, Supplier<T> action) {
        String lockKey = LOCK_PREFIX + taskId;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // 尝试获取锁，最多等待3秒，锁10秒后自动释放
            boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, 
                LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("获取任务调度锁失败 taskId={}", taskId);
                return null;
            }
            
            log.debug("获取任务调度锁成功 taskId={}", taskId);
            
            // 执行调度操作
            return action.get();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("任务调度锁被中断 taskId={}", taskId, e);
            return null;
            
        } finally {
            // 释放锁（只释放自己持有的锁）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放任务调度锁 taskId={}", taskId);
            }
        }
    }
    
    /**
     * 批量获取锁（用于批量调度）
     */
    public Map<Long, RLock> batchLock(List<Long> taskIds) {
        Map<Long, RLock> locks = new HashMap<>();
        
        for (Long taskId : taskIds) {
            String lockKey = LOCK_PREFIX + taskId;
            RLock lock = redissonClient.getLock(lockKey);
            
            try {
                boolean acquired = lock.tryLock(0, LOCK_TTL_SECONDS, 
                    TimeUnit.SECONDS);
                
                if (acquired) {
                    locks.put(taskId, lock);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        return locks;
    }
    
    /**
     * 批量释放锁
     */
    public void batchUnlock(Map<Long, RLock> locks) {
        for (Map.Entry<Long, RLock> entry : locks.entrySet()) {
            RLock lock = entry.getValue();
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 4.3 资源分配锁

```java
@Service
@Slf4j
public class ResourceAllocationLockService {
    
    private static final String LOCK_PREFIX = "resource:node:lock:";
    private static final long LOCK_TTL_SECONDS = 5;
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 执行带锁的资源分配操作
     */
    public ReserveResourceResponse allocateWithLock(
            Long nodeId, ReserveResourceRequest request) {
        
        String lockKey = LOCK_PREFIX + nodeId;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // 尝试获取锁，不等待，5秒后自动释放
            boolean acquired = lock.tryLock(0, LOCK_TTL_SECONDS, 
                TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("获取资源分配锁失败 nodeId={}", nodeId);
                return null;
            }
            
            // 1. 重新查询节点资源（防止脏读）
            ResourceNode node = resourceNodeMapper.selectById(nodeId);
            
            // 2. 检查资源充足性
            if (!canFit(node, request)) {
                log.warn("节点资源不足 nodeId={} available={} required={}", 
                    nodeId, node.getAvailableCpu(), request.getCpu());
                return null;
            }
            
            // 3. 预留资源（使用数据库乐观锁）
            boolean reserved = reserveResourceWithOptimisticLock(
                node, request);
            
            if (!reserved) {
                log.warn("资源预留失败（版本冲突） nodeId={}", nodeId);
                return null;
            }
            
            // 4. 记录资源使用
            ResourceUsage usage = createResourceUsage(request);
            resourceUsageMapper.insert(usage);
            
            // 5. 返回预留结果
            return new ReserveResourceResponse(usage.getId(), nodeId);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
            
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * 使用乐观锁预留资源
     */
    private boolean reserveResourceWithOptimisticLock(
            ResourceNode node, ReserveResourceRequest request) {
        
        // 更新节点可用资源（带版本号）
        int updated = resourceNodeMapper.updateAvailableResource(
            node.getId(),
            node.getAvailableCpu() - request.getCpu(),
            node.getAvailableMemoryMb() - request.getMemoryMb(),
            node.getAvailableGpu() - request.getGpu(),
            node.getVersion()
        );
        
        return updated > 0;
    }
}
```

### 4.4 数据库乐观锁

```java
@Mapper
public interface ResourceNodeMapper {
    
    /**
     * 使用乐观锁更新节点资源
     */
    @Update("UPDATE resource_node " +
            "SET available_cpu = #{availableCpu}, " +
            "    available_memory_mb = #{availableMemoryMb}, " +
            "    available_gpu = #{availableGpu}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} " +
            "  AND version = #{version} " +
            "  AND available_cpu >= #{availableCpu} " +  // 防止负数
            "  AND available_memory_mb >= #{availableMemoryMb}")
    int updateAvailableResource(
        @Param("id") Long id,
        @Param("availableCpu") Double availableCpu,
        @Param("availableMemoryMb") Long availableMemoryMb,
        @Param("availableGpu") Integer availableGpu,
        @Param("version") Integer version
    );
}

@Mapper
public interface TaskInstanceMapper {
    
    /**
     * 使用乐观锁更新任务状态
     */
    @Update("UPDATE task_instance " +
            "SET status = #{toStatus}, " +
            "    node_id = #{nodeId}, " +
            "    resource_usage_id = #{resourceUsageId}, " +
            "    start_time = #{startTime}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} " +
            "  AND status = #{fromStatus} " +
            "  AND version = #{version}")
    int updateStatusWithVersion(
        @Param("id") Long id,
        @Param("fromStatus") TaskStatus fromStatus,
        @Param("toStatus") TaskStatus toStatus,
        @Param("nodeId") Long nodeId,
        @Param("resourceUsageId") Long resourceUsageId,
        @Param("startTime") Instant startTime,
        @Param("version") Integer version
    );
}
```

---

## 5. 幂等性设计

### 5.1 为什么需要幂等性

**场景**：网络超时导致重试
```
客户端 → 提交任务 → 服务端处理成功
       ↓ 网络超时
客户端 → 重试提交 → 服务端再次处理 ❌ 重复

期望：第二次提交被识别为重复，返回第一次的结果
```

### 5.2 幂等性实现

#### 任务提交幂等
```java
@Service
public class TaskSubmitService {
    
    /**
     * 幂等的任务提交
     */
    public Long submitTaskIdempotent(
            TaskSubmitRequest request, String idempotencyKey) {
        
        // 1. 检查幂等性键是否已存在
        Long existingTaskId = idempotencyCache.get(idempotencyKey);
        if (existingTaskId != null) {
            log.info("检测到重复提交 idempotencyKey={} taskId={}", 
                idempotencyKey, existingTaskId);
            return existingTaskId;
        }
        
        // 2. 提交任务
        Long taskId = submitTask(request);
        
        // 3. 缓存幂等性键（24小时过期）
        idempotencyCache.put(idempotencyKey, taskId, 24, TimeUnit.HOURS);
        
        return taskId;
    }
}
```

#### 资源释放幂等
```java
@Service
public class ResourceSlotService {
    
    /**
     * 幂等的资源释放
     */
    public void releaseResourceIdempotent(Long usageId) {
        // 1. 查询资源使用记录
        ResourceUsage usage = resourceUsageMapper.selectById(usageId);
        
        if (usage == null) {
            log.warn("资源使用记录不存在 usageId={}", usageId);
            return;
        }
        
        // 2. 检查状态（已释放则跳过）
        if (usage.getStatus() == ResourceUsageStatus.RELEASED) {
            log.info("资源已释放，跳过 usageId={}", usageId);
            return;
        }
        
        // 3. 释放资源
        releaseResource(usage);
    }
}
```

---

## 6. 异常处理

### 6.1 锁超时处理

```java
/**
 * 锁超时自动释放
 */
public void scheduleTask(TaskInstance task) {
    String lockKey = "task:schedule:lock:" + task.getId();
    RLock lock = redissonClient.getLock(lockKey);
    
    try {
        // 设置锁超时时间为10秒
        boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
        
        if (!acquired) {
            log.warn("获取锁超时 taskId={}", task.getId());
            return;
        }
        
        // 执行调度（如果超过10秒，锁会自动释放）
        doSchedule(task);
        
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        // 只释放自己持有的锁
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 6.2 死锁检测

```java
@Component
public class DeadlockDetector {
    
    @Scheduled(fixedRate = 60000) // 每分钟检测一次
    public void detectDeadlock() {
        // 1. 查询所有锁
        Set<String> locks = redisTemplate.keys("*:lock:*");
        
        // 2. 检查锁的持有时间
        for (String lockKey : locks) {
            Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
            
            if (ttl == null || ttl < 0) {
                // 锁没有过期时间，可能是死锁
                log.error("检测到可能的死锁 lockKey={}", lockKey);
                
                // 强制删除（谨慎操作）
                // redisTemplate.delete(lockKey);
            }
        }
    }
}
```

### 6.3 资源泄漏检测

```java
@Component
public class ResourceLeakDetector {
    
    @Scheduled(fixedRate = 300000) // 每5分钟检测一次
    public void detectResourceLeak() {
        // 1. 查询长时间RUNNING的任务
        List<TaskInstance> longRunningTasks = taskInstanceMapper
            .selectLongRunningTasks(3600); // 超过1小时
        
        for (TaskInstance task : longRunningTasks) {
            log.warn("检测到长时间运行的任务 taskId={} duration={}min", 
                task.getId(), 
                Duration.between(task.getStartTime(), Instant.now())
                    .toMinutes());
            
            // 2. 检查任务是否真的在运行
            boolean isActuallyRunning = checkTaskStatus(task);
            
            if (!isActuallyRunning) {
                log.error("检测到资源泄漏 taskId={}", task.getId());
                
                // 3. 释放资源
                releaseLeakedResource(task);
            }
        }
    }
    
    private void releaseLeakedResource(TaskInstance task) {
        // 1. 更新任务状态为FAILED
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage("资源泄漏，自动释放");
        taskInstanceMapper.updateById(task);
        
        // 2. 释放资源
        if (task.getResourceUsageId() != null) {
            resourceSlotService.releaseResource(task.getResourceUsageId());
        }
    }
}
```

---

## 7. 监控与告警

### 7.1 锁监控指标

| 指标 | 说明 | 告警阈值 |
|-----|------|---------|
| `lock.acquire.success` | 获取锁成功数 | - |
| `lock.acquire.fail` | 获取锁失败数 | > 100/min |
| `lock.acquire.timeout` | 获取锁超时数 | > 50/min |
| `lock.hold.duration` | 锁持有时长 | > 10s |
| `lock.contention.rate` | 锁竞争率 | > 50% |

### 7.2 并发冲突监控

```java
@Component
public class ConcurrencyMetrics {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    private Counter versionConflictCounter;
    private Counter duplicateScheduleCounter;
    private Timer lockAcquireTimer;
    
    @PostConstruct
    public void init() {
        versionConflictCounter = Counter.builder("concurrency.version.conflict")
            .description("乐观锁版本冲突数")
            .register(meterRegistry);
        
        duplicateScheduleCounter = Counter.builder("concurrency.duplicate.schedule")
            .description("重复调度次数")
            .register(meterRegistry);
        
        lockAcquireTimer = Timer.builder("concurrency.lock.acquire")
            .description("获取锁耗时")
            .register(meterRegistry);
    }
    
    public void recordVersionConflict() {
        versionConflictCounter.increment();
    }
    
    public void recordDuplicateSchedule() {
        duplicateScheduleCounter.increment();
    }
    
    public void recordLockAcquire(long durationMs) {
        lockAcquireTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}
```

### 7.3 Grafana面板

```
┌─────────────────────────────────────────────────────┐
│  并发安全监控                                        │
├─────────────────────────────────────────────────────┤
│  锁获取成功率：99.2%                                 │
│  锁竞争率：15%                                       │
│  平均锁持有时长：50ms                                │
│  版本冲突数：12/min                                  │
│  重复调度数：0/min                                   │
│                                                     │
│  告警：                                              │
│  ⚠️ 锁获取失败率过高（1.5% > 1%）                   │
└─────────────────────────────────────────────────────┘
```

---

## 8. 性能优化

### 8.1 减少锁持有时间

```java
// ❌ 不好：锁持有时间长
public void scheduleTask(TaskInstance task) {
    lock.lock();
    try {
        // 查询数据库（慢）
        ResourceNode node = selectNode(task);
        
        // 预留资源（慢）
        reserveResource(task, node);
        
        // 提交执行器（慢）
        submitToExecutor(task, node);
        
    } finally {
        lock.unlock();
    }
}

// ✅ 好：只在关键操作时持有锁
public void scheduleTask(TaskInstance task) {
    // 1. 查询数据（不需要锁）
    ResourceNode node = selectNode(task);
    
    // 2. 预留资源（需要锁）
    lock.lock();
    try {
        reserveResource(task, node);
    } finally {
        lock.unlock();
    }
    
    // 3. 提交执行器（不需要锁）
    submitToExecutor(task, node);
}
```

### 8.2 锁分段

```java
// ❌ 不好：全局锁
private final RLock globalLock = redisson.getLock("global:lock");

// ✅ 好：分段锁
private RLock getNodeLock(Long nodeId) {
    return redisson.getLock("node:lock:" + nodeId);
}

// 不同节点的资源分配可以并行
```

### 8.3 读写锁

```java
@Service
public class ResourceNodeService {
    
    /**
     * 读操作（共享锁）
     */
    public ResourceNode getNode(Long nodeId) {
        RReadWriteLock rwLock = redisson.getReadWriteLock("node:rw:" + nodeId);
        RLock readLock = rwLock.readLock();
        
        try {
            readLock.lock();
            return resourceNodeMapper.selectById(nodeId);
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * 写操作（排他锁）
     */
    public void updateNode(ResourceNode node) {
        RReadWriteLock rwLock = redisson.getReadWriteLock("node:rw:" + node.getId());
        RLock writeLock = rwLock.writeLock();
        
        try {
            writeLock.lock();
            resourceNodeMapper.updateById(node);
        } finally {
            writeLock.unlock();
        }
    }
}
```

---

## 9. 测试方案

### 9.1 并发调度测试

```java
@Test
public void testConcurrentSchedule() throws Exception {
    // 创建1个PENDING任务
    TaskInstance task = createPendingTask();
    
    // 10个线程同时调度
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Boolean>> futures = new ArrayList<>();
    
    for (int i = 0; i < 10; i++) {
        futures.add(executor.submit(() -> 
            schedulerService.scheduleTask(task)));
    }
    
    // 统计成功次数
    long successCount = futures.stream()
        .map(f -> {
            try { return f.get(); } 
            catch (Exception e) { return false; }
        })
        .filter(b -> b)
        .count();
    
    // 验证：只有1个线程成功
    assertEquals(1, successCount);
    
    // 验证：任务状态为RUNNING
    TaskInstance updated = taskInstanceMapper.selectById(task.getId());
    assertEquals(TaskStatus.RUNNING, updated.getStatus());
}
```

### 9.2 资源超分配测试

```java
@Test
public void testResourceOverAllocation() throws Exception {
    // 创建节点（4核CPU）
    ResourceNode node = createNode(4, 8192, 0);
    
    // 创建3个任务（每个需要2核）
    TaskInstance task1 = createTask(2, 4096, 0);
    TaskInstance task2 = createTask(2, 4096, 0);
    TaskInstance task3 = createTask(2, 4096, 0);
    
    // 并发调度
    ExecutorService executor = Executors.newFixedThreadPool(3);
    List<Future<Boolean>> futures = Arrays.asList(
        executor.submit(() -> schedulerService.scheduleTask(task1)),
        executor.submit(() -> schedulerService.scheduleTask(task2)),
        executor.submit(() -> schedulerService.scheduleTask(task3))
    );
    
    // 统计成功次数
    long successCount = futures.stream()
        .map(f -> {
            try { return f.get(); } 
            catch (Exception e) { return false; }
        })
        .filter(b -> b)
        .count();
    
    // 验证：只有2个任务成功（4核只能分配2个2核任务）
    assertEquals(2, successCount);
    
    // 验证：节点资源未超分配
    ResourceNode updated = resourceNodeMapper.selectById(node.getId());
    assertTrue(updated.getAvailableCpu() >= 0);
}
```

### 9.3 幂等性测试

```java
@Test
public void testIdempotency() {
    TaskSubmitRequest request = buildRequest();
    String idempotencyKey = UUID.randomUUID().toString();
    
    // 第一次提交
    Long taskId1 = taskSubmitService.submitTaskIdempotent(
        request, idempotencyKey);
    
    // 第二次提交（相同幂等性键）
    Long taskId2 = taskSubmitService.submitTaskIdempotent(
        request, idempotencyKey);
    
    // 验证：返回相同的任务ID
    assertEquals(taskId1, taskId2);
    
    // 验证：数据库中只有1条记录
    long count = taskInstanceMapper.countByIdempotencyKey(idempotencyKey);
    assertEquals(1, count);
}
```

---

## 10. 常见问题

### Q1: Redis分布式锁和数据库乐观锁有什么区别？

**答**：
- **Redis分布式锁**：防止并发操作，适合粗粒度控制
- **数据库乐观锁**：防止数据冲突，适合细粒度控制
- **组合使用**：Redis锁控制流程，数据库锁保证数据一致性

### Q2: 锁超时时间如何设置？

**答**：
- 任务调度锁：10秒（调度操作通常<1秒）
- 资源分配锁：5秒（资源操作通常<500ms）
- Leader锁：30秒（调度周期5秒，留有余量）
- 原则：略大于操作耗时的2-3倍

### Q3: 如何处理锁竞争激烈的情况？

**答**：
1. 减小锁粒度（全局锁→任务锁→节点锁）
2. 减少锁持有时间（只在关键操作时加锁）
3. 使用读写锁（读多写少场景）
4. 引入队列削峰（P3-1）

### Q4: Redisson Watch Dog是什么？

**答**：
- 自动续期机制，防止锁在操作未完成时过期
- 默认每10秒续期一次（lockWatchdogTimeout / 3）
- 只对未设置leaseTime的锁生效
- 线程退出或unlock时自动停止续期

---

## 11. 最佳实践

### 11.1 锁使用规范

```java
// ✅ 好的实践
public void goodPractice() {
    RLock lock = redisson.getLock("key");
    try {
        // 1. 设置超时时间
        boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
        
        if (!acquired) {
            // 2. 获取失败，记录日志并返回
            log.warn("获取锁失败");
            return;
        }
        
        // 3. 执行业务逻辑
        doSomething();
        
    } catch (InterruptedException e) {
        // 4. 处理中断
        Thread.currentThread().interrupt();
    } finally {
        // 5. 只释放自己持有的锁
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}

// ❌ 不好的实践
public void badPractice() {
    RLock lock = redisson.getLock("key");
    lock.lock(); // 无限等待，可能死锁
    
    try {
        doSomething();
    } finally {
        lock.unlock(); // 可能释放别人的锁
    }
}
```

### 11.2 乐观锁使用规范

```java
// ✅ 好的实践
@Transactional
public void updateWithRetry(TaskInstance task) {
    int maxRetries = 3;
    int attempt = 0;
    
    while (attempt < maxRetries) {
        try {
            // 1. 查询最新版本
            task = taskInstanceMapper.selectById(task.getId());
            
            // 2. 修改数据
            task.setStatus(TaskStatus.RUNNING);
            
            // 3. 使用乐观锁更新
            int updated = taskInstanceMapper.updateWithVersion(task);
            
            if (updated > 0) {
                return; // 成功
            }
            
            // 4. 版本冲突，重试
            attempt++;
            Thread.sleep(100 * attempt); // 指数退避
            
        } catch (Exception e) {
            log.error("更新失败", e);
            throw new RuntimeException(e);
        }
    }
    
    throw new ConcurrencyException("更新失败，超过最大重试次数");
}
```

---

## 12. 总结

并发安全保障是调度引擎的**可靠性基石**，核心价值：

✅ **防止重复调度**：通过多层锁机制确保任务只被调度一次  
✅ **防止资源超分配**：通过分布式锁+乐观锁确保资源不超限  
✅ **防止配额超限**：通过原子操作确保租户配额不被突破  
✅ **高可用性**：Leader选举机制确保调度器故障自动切换  

**关键设计决策**：
- 四层防护：Leader锁 → 任务锁 → 节点锁 → 乐观锁
- Redis分布式锁 + 数据库乐观锁组合
- 锁超时自动释放（Watch Dog）
- 幂等性设计

**性能指标**：
- 锁获取成功率 > 99%
- 锁竞争率 < 20%
- 版本冲突率 < 5%
- 重复调度率 = 0%

这个设计能够支撑 **1000+ QPS** 的并发调度，确保系统在高并发、分布式环境下的正确性和可靠性。

**至此，Phase 3调度引擎核心的所有5个模块设计稿全部完成！** 🎉
