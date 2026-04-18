# P3-2: FIFO调度器设计稿

## 1. 文档目标

本文档详细设计最基础的FIFO（先进先出）调度器，作为调度引擎的核心起点，解决以下问题：

- 如何从PENDING任务中选择下一个执行的任务
- 如何将任务分配到可用的资源节点
- 如何更新任务状态并触发执行
- 如何保证调度的正确性和可靠性

**核心价值**：建立调度引擎的基本框架，为后续优先级调度、资源感知调度奠定基础。

---

## 2. 调度器职责

### 2.1 核心职责

```
┌─────────────────────────────────────────────────────┐
│                  FIFO调度器                          │
├─────────────────────────────────────────────────────┤
│  1. 扫描PENDING任务（按提交时间排序）                 │
│  2. 检查租户配额是否充足                             │
│  3. 选择可用的资源节点                               │
│  4. 预留资源并更新任务状态                           │
│  5. 提交任务到执行器                                 │
└─────────────────────────────────────────────────────┘
```

### 2.2 调度器在系统中的位置

```
任务提交 → 削峰队列 → 数据库(PENDING)
                         ↓
                    【FIFO调度器】← 定时扫描
                         ↓
                    资源预留 → 状态更新(RUNNING)
                         ↓
                    执行器 → Worker节点
```

---

## 3. 调度算法

### 3.1 FIFO算法原理

**核心思想**：按照任务提交时间的先后顺序进行调度，先提交的任务先执行。

```sql
-- 调度查询
SELECT * FROM task_instance
WHERE status = 'PENDING'
  AND tenant_id IN (SELECT tenant_id FROM tenant WHERE status = 'ACTIVE')
ORDER BY submit_time ASC  -- 关键：按提交时间排序
LIMIT 100;
```

**优点**：
- ✅ 实现简单，逻辑清晰
- ✅ 公平性好，不会出现任务饿死
- ✅ 可预测性强，用户知道任务何时执行

**缺点**：
- ❌ 不考虑任务优先级
- ❌ 不考虑资源需求差异
- ❌ 可能导致资源利用率不高

### 3.2 调度流程

```
┌─────────────────────────────────────────────────────┐
│  Step 1: 扫描PENDING任务                             │
│  ↓                                                   │
│  从数据库查询最早的100个PENDING任务                   │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Step 2: 配额检查（for each task）                   │
│  ↓                                                   │
│  检查租户配额是否充足                                 │
│  ├─ 充足 → 继续                                      │
│  └─ 不足 → 跳过，保持PENDING                         │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Step 3: 节点选择                                    │
│  ↓                                                   │
│  查询ONLINE状态的资源节点                            │
│  ├─ 有可用节点 → 选择第一个                          │
│  └─ 无可用节点 → 跳过，保持PENDING                   │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Step 4: 资源预留                                    │
│  ↓                                                   │
│  调用资源槽位服务预留资源                             │
│  ├─ 成功 → 继续                                      │
│  └─ 失败 → 跳过，保持PENDING                         │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Step 5: 状态更新                                    │
│  ↓                                                   │
│  更新任务状态：PENDING → RUNNING                     │
│  记录分配的节点ID和开始时间                          │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Step 6: 提交执行                                    │
│  ↓                                                   │
│  调用执行器API，提交任务到Worker节点                 │
└─────────────────────────────────────────────────────┘
```

---

## 4. 核心实现

### 4.1 调度器主类

```java
@Service
@Slf4j
public class FifoSchedulerService {
    
    private static final int BATCH_SIZE = 100;
    private static final int SCHEDULE_INTERVAL_MS = 5000; // 5秒
    
    @Autowired
    private TaskInstanceMapper taskInstanceMapper;
    
    @Autowired
    private ResourceNodeMapper resourceNodeMapper;
    
    @Autowired
    private ResourceSlotService resourceSlotService;
    
    @Autowired
    private ResourceQuotaService resourceQuotaService;
    
    @Autowired
    private TaskExecutorService taskExecutorService;
    
    @Autowired
    private RedissonClient redissonClient;
    
    private volatile boolean isLeader = false;
    
    /**
     * 调度主循环（定时任务）
     */
    @Scheduled(fixedDelay = SCHEDULE_INTERVAL_MS)
    public void scheduleLoop() {
        // 1. Leader选举
        if (!tryAcquireLeadership()) {
            log.debug("非Leader节点，跳过调度");
            return;
        }
        
        log.info("开始调度周期");
        
        try {
            // 2. 扫描PENDING任务
            List<TaskInstance> pendingTasks = scanPendingTasks();
            
            if (pendingTasks.isEmpty()) {
                log.debug("无待调度任务");
                return;
            }
            
            log.info("扫描到待调度任务 count={}", pendingTasks.size());
            
            // 3. 逐个调度
            int successCount = 0;
            int skipCount = 0;
            
            for (TaskInstance task : pendingTasks) {
                boolean scheduled = scheduleTask(task);
                if (scheduled) {
                    successCount++;
                } else {
                    skipCount++;
                }
            }
            
            log.info("调度周期完成 success={} skip={}", successCount, skipCount);
            
        } catch (Exception e) {
            log.error("调度周期异常", e);
        }
    }
    
    /**
     * Leader选举（使用Redis分布式锁）
     */
    private boolean tryAcquireLeadership() {
        String lockKey = "scheduler:leader:lock";
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // 尝试获取锁，锁30秒后自动释放
            boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            
            if (acquired && !isLeader) {
                isLeader = true;
                log.info("成为Scheduler Leader");
            }
            
            return acquired;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 扫描PENDING任务
     */
    private List<TaskInstance> scanPendingTasks() {
        return taskInstanceMapper.selectPendingTasks(BATCH_SIZE);
    }
    
    /**
     * 调度单个任务
     */
    private boolean scheduleTask(TaskInstance task) {
        String lockKey = "task:schedule:lock:" + task.getId();
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // 1. 加锁（防止重复调度）
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("任务正在被其他实例调度 taskId={}", task.getId());
                return false;
            }
            
            // 2. 双重检查任务状态
            task = taskInstanceMapper.selectById(task.getId());
            if (task.getStatus() != TaskStatus.PENDING) {
                log.warn("任务状态已变更 taskId={} status={}", 
                    task.getId(), task.getStatus());
                return false;
            }
            
            // 3. 检查租户配额
            if (!checkQuota(task)) {
                log.info("租户配额不足 taskId={} tenantId={}", 
                    task.getId(), task.getTenantId());
                return false;
            }
            
            // 4. 选择资源节点
            ResourceNode node = selectNode(task);
            if (node == null) {
                log.info("无可用节点 taskId={}", task.getId());
                return false;
            }
            
            // 5. 预留资源
            ReserveResourceResponse reservation = reserveResource(task, node);
            if (reservation == null) {
                log.info("资源预留失败 taskId={} nodeId={}", 
                    task.getId(), node.getId());
                return false;
            }
            
            // 6. 更新任务状态
            boolean updated = updateTaskStatus(task, node, reservation);
            if (!updated) {
                // 状态更新失败，释放资源
                resourceSlotService.releaseResource(reservation.getUsageId());
                return false;
            }
            
            // 7. 提交执行
            submitToExecutor(task, node);
            
            log.info("任务调度成功 taskId={} nodeId={}", 
                task.getId(), node.getId());
            
            return true;
            
        } catch (Exception e) {
            log.error("任务调度失败 taskId={}", task.getId(), e);
            return false;
            
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * 检查租户配额
     */
    private boolean checkQuota(TaskInstance task) {
        ResourceRequirement requirement = parseRequirement(
            task.getResourceRequirement());
        
        QuotaCheckRequest request = new QuotaCheckRequest();
        request.setTenantId(task.getTenantId());
        request.setCpu(requirement.getCpu());
        request.setMemoryMb(requirement.getMemoryMb());
        request.setGpu(requirement.getGpu());
        
        QuotaCheckResponse response = resourceQuotaService.checkQuota(request);
        
        return response.isAllowed();
    }
    
    /**
     * 选择资源节点（简单轮询）
     */
    private ResourceNode selectNode(TaskInstance task) {
        List<ResourceNode> onlineNodes = resourceNodeMapper.selectOnlineNodes();
        
        if (onlineNodes.isEmpty()) {
            return null;
        }
        
        // FIFO调度器：简单选择第一个节点
        // 后续P3-4会实现Best Fit算法
        return onlineNodes.get(0);
    }
    
    /**
     * 预留资源
     */
    private ReserveResourceResponse reserveResource(
            TaskInstance task, ResourceNode node) {
        
        ResourceRequirement requirement = parseRequirement(
            task.getResourceRequirement());
        
        ReserveResourceRequest request = new ReserveResourceRequest();
        request.setTenantId(task.getTenantId());
        request.setTaskInstanceId(task.getId());
        request.setNodeId(node.getId());
        request.setCpu(requirement.getCpu());
        request.setMemoryMb(requirement.getMemoryMb());
        request.setGpu(requirement.getGpu());
        
        try {
            return resourceSlotService.reserveResource(request);
        } catch (Exception e) {
            log.error("资源预留异常 taskId={} nodeId={}", 
                task.getId(), node.getId(), e);
            return null;
        }
    }
    
    /**
     * 更新任务状态
     */
    @Transactional
    private boolean updateTaskStatus(TaskInstance task, 
            ResourceNode node, ReserveResourceResponse reservation) {
        
        task.setStatus(TaskStatus.RUNNING);
        task.setNodeId(node.getId());
        task.setResourceUsageId(reservation.getUsageId());
        task.setStartTime(Instant.now());
        task.setUpdatedAt(Instant.now());
        
        // 使用乐观锁更新
        int updated = taskInstanceMapper.updateStatusWithVersion(
            task.getId(), 
            TaskStatus.PENDING, 
            TaskStatus.RUNNING,
            task.getVersion()
        );
        
        if (updated == 0) {
            log.warn("任务状态更新失败（版本冲突） taskId={}", task.getId());
            return false;
        }
        
        return true;
    }
    
    /**
     * 提交到执行器
     */
    private void submitToExecutor(TaskInstance task, ResourceNode node) {
        TaskExecutionRequest request = new TaskExecutionRequest();
        request.setTaskInstanceId(task.getId());
        request.setTaskType(task.getTaskType());
        request.setExecutorConfig(task.getExecutorConfig());
        request.setParameters(task.getParameters());
        request.setNodeHost(node.getNodeHost());
        request.setNodePort(node.getNodePort());
        
        // 异步提交（不阻塞调度循环）
        CompletableFuture.runAsync(() -> {
            try {
                taskExecutorService.submitTask(request);
            } catch (Exception e) {
                log.error("提交执行器失败 taskId={}", task.getId(), e);
                // 失败处理：标记任务为FAILED，释放资源
                handleExecutorSubmitFailure(task);
            }
        });
    }
    
    /**
     * 执行器提交失败处理
     */
    private void handleExecutorSubmitFailure(TaskInstance task) {
        // 1. 更新任务状态为FAILED
        task.setStatus(TaskStatus.FAILED);
        task.setEndTime(Instant.now());
        task.setErrorMessage("提交执行器失败");
        taskInstanceMapper.updateById(task);
        
        // 2. 释放资源
        if (task.getResourceUsageId() != null) {
            resourceSlotService.releaseResource(task.getResourceUsageId());
        }
    }
    
    /**
     * 解析资源需求
     */
    private ResourceRequirement parseRequirement(String json) {
        return JsonUtils.fromJson(json, ResourceRequirement.class);
    }
}
```

### 4.2 Mapper实现

```java
@Mapper
public interface TaskInstanceMapper {
    
    /**
     * 查询PENDING任务（按提交时间排序）
     */
    @Select("SELECT * FROM task_instance " +
            "WHERE status = 'PENDING' " +
            "ORDER BY submit_time ASC " +
            "LIMIT #{limit}")
    List<TaskInstance> selectPendingTasks(@Param("limit") int limit);
    
    /**
     * 使用乐观锁更新任务状态
     */
    @Update("UPDATE task_instance " +
            "SET status = #{toStatus}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} " +
            "  AND status = #{fromStatus} " +
            "  AND version = #{version}")
    int updateStatusWithVersion(
        @Param("id") Long id,
        @Param("fromStatus") TaskStatus fromStatus,
        @Param("toStatus") TaskStatus toStatus,
        @Param("version") Integer version
    );
}
```

### 4.3 资源需求模型

```java
@Data
public class ResourceRequirement {
    private Double cpu;        // CPU核数
    private Long memoryMb;     // 内存MB
    private Integer gpu;       // GPU卡数
    
    // 可选字段
    private String nodeType;   // 节点类型要求（CPU/GPU/MIXED）
    private List<String> tags; // 节点标签要求
}
```

---

## 5. 并发安全保障

### 5.1 三层锁机制

```
┌─────────────────────────────────────────────────────┐
│  Layer 1: Leader选举锁                               │
│  ├─ 确保只有一个调度器实例在工作                      │
│  └─ Key: scheduler:leader:lock                       │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Layer 2: 任务调度锁                                 │
│  ├─ 防止同一任务被多次调度                           │
│  └─ Key: task:schedule:lock:{taskId}                 │
└─────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────┐
│  Layer 3: 数据库乐观锁                               │
│  ├─ 最终的状态更新保障                               │
│  └─ WHERE version = #{version}                       │
└─────────────────────────────────────────────────────┘
```

### 5.2 并发场景分析

**场景1：多个调度器实例同时启动**
```
实例A → 尝试获取Leader锁 → ✅ 成功 → 开始调度
实例B → 尝试获取Leader锁 → ❌ 失败 → 等待
实例C → 尝试获取Leader锁 → ❌ 失败 → 等待
```

**场景2：Leader锁过期，新Leader接管**
```
实例A → Leader锁过期（30秒）→ 失去Leader身份
实例B → 获取Leader锁 → ✅ 成功 → 接管调度
```

**场景3：两个实例同时调度同一任务**
```
实例A → 获取任务锁 → ✅ 成功 → 调度任务
实例B → 获取任务锁 → ❌ 失败 → 跳过任务
```

**场景4：状态更新冲突**
```
实例A → 更新状态（version=0） → ✅ 成功（version变为1）
实例B → 更新状态（version=0） → ❌ 失败（version已经是1）
```

---

## 6. 监控与告警

### 6.1 关键指标

| 指标 | 说明 | 告警阈值 |
|-----|------|---------|
| `scheduler.pending.tasks` | 待调度任务数 | > 1000 |
| `scheduler.schedule.success` | 调度成功数 | - |
| `scheduler.schedule.skip` | 调度跳过数 | > 100/min |
| `scheduler.schedule.latency` | 调度延迟 | > 10s |
| `scheduler.leader.status` | Leader状态 | 0（无Leader） |

### 6.2 日志记录

```java
// 调度周期开始
log.info("开始调度周期 pendingCount={}", pendingTasks.size());

// 单个任务调度
log.info("任务调度成功 taskId={} nodeId={} elapsed={}ms", 
    task.getId(), node.getId(), elapsed);

// 调度周期结束
log.info("调度周期完成 success={} skip={} elapsed={}ms", 
    successCount, skipCount, totalElapsed);
```

---

## 7. 性能优化

### 7.1 批量查询优化

```java
// 优化前：逐个查询节点
for (TaskInstance task : tasks) {
    ResourceNode node = resourceNodeMapper.selectById(task.getNodeId());
}

// 优化后：批量查询
List<Long> nodeIds = tasks.stream()
    .map(TaskInstance::getNodeId)
    .collect(Collectors.toList());
List<ResourceNode> nodes = resourceNodeMapper.selectByIds(nodeIds);
```

### 7.2 调度间隔调优

| 间隔 | 优点 | 缺点 | 推荐场景 |
|-----|------|------|---------|
| 1秒 | 实时性高 | CPU占用高 | 高并发场景 |
| 5秒 | 均衡 | - | **推荐** |
| 10秒 | CPU占用低 | 延迟高 | 低负载场景 |

### 7.3 数据库索引

```sql
-- 关键索引
CREATE INDEX idx_status_submit_time ON task_instance(status, submit_time);
CREATE INDEX idx_tenant_status ON task_instance(tenant_id, status);
CREATE INDEX idx_node_status ON resource_node(status);
```

---

## 8. 测试方案

### 8.1 单元测试

```java
@Test
public void testScheduleTask_Success() {
    // 准备数据
    TaskInstance task = createPendingTask();
    ResourceNode node = createOnlineNode();
    
    // 执行调度
    boolean scheduled = schedulerService.scheduleTask(task);
    
    // 验证结果
    assertTrue(scheduled);
    
    TaskInstance updated = taskInstanceMapper.selectById(task.getId());
    assertEquals(TaskStatus.RUNNING, updated.getStatus());
    assertNotNull(updated.getNodeId());
}

@Test
public void testScheduleTask_QuotaExceeded() {
    // 准备数据：配额已用完
    TaskInstance task = createPendingTask();
    mockQuotaExceeded(task.getTenantId());
    
    // 执行调度
    boolean scheduled = schedulerService.scheduleTask(task);
    
    // 验证结果：调度失败，任务保持PENDING
    assertFalse(scheduled);
    
    TaskInstance updated = taskInstanceMapper.selectById(task.getId());
    assertEquals(TaskStatus.PENDING, updated.getStatus());
}
```

### 8.2 集成测试

```java
@Test
public void testScheduleLoop_EndToEnd() {
    // 1. 创建10个PENDING任务
    List<TaskInstance> tasks = createPendingTasks(10);
    
    // 2. 创建2个ONLINE节点
    List<ResourceNode> nodes = createOnlineNodes(2);
    
    // 3. 执行调度周期
    schedulerService.scheduleLoop();
    
    // 4. 验证结果
    List<TaskInstance> running = taskInstanceMapper.selectByStatus(
        TaskStatus.RUNNING);
    assertTrue(running.size() > 0);
}
```

### 8.3 并发测试

```java
@Test
public void testConcurrentSchedule() throws Exception {
    TaskInstance task = createPendingTask();
    
    // 10个线程同时调度同一任务
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
}
```

---

## 9. 常见问题

### Q1: 为什么使用定时任务而不是事件驱动？

**答**：
- 定时任务实现简单，易于理解和维护
- 避免了事件丢失的风险
- 可以批量处理，提高效率
- 后续可以平滑升级为事件驱动

### Q2: 调度器挂了怎么办？

**答**：
- Leader选举机制保证高可用
- 其他实例会自动接管
- 任务状态持久化在数据库，不会丢失

### Q3: 如何保证任务不被重复调度？

**答**：
- Redis分布式锁防止并发调度
- 数据库乐观锁防止状态冲突
- 双重检查任务状态

### Q4: PENDING任务太多怎么办？

**答**：
- 增加调度器实例（通过Leader选举自动切换）
- 减少调度间隔（从5秒改为1秒）
- 增加资源节点

---

## 10. 后续优化方向

### 10.1 P3-3: 优先级调度

在FIFO基础上增加优先级维度：

```sql
SELECT * FROM task_instance
WHERE status = 'PENDING'
ORDER BY priority DESC, submit_time ASC  -- 先按优先级，再按时间
LIMIT 100;
```

### 10.2 P3-4: 资源感知调度

选择节点时考虑资源匹配度：

```java
private ResourceNode selectNode(TaskInstance task) {
    List<ResourceNode> nodes = resourceNodeMapper.selectOnlineNodes();
    
    // Best Fit算法：选择资源最匹配的节点
    return nodes.stream()
        .filter(node -> canFit(node, task))
        .min(Comparator.comparing(node -> 
            calculateWaste(node, task)))
        .orElse(null);
}
```

### 10.3 事件驱动调度

使用消息队列替代定时任务：

```
任务提交 → MQ(PENDING事件) → 调度器消费 → 立即调度
```

---

## 11. 总结

FIFO调度器是调度引擎的**基础实现**，核心价值：

✅ **建立调度框架**：定义了调度器的基本结构和流程  
✅ **保证正确性**：通过三层锁机制确保并发安全  
✅ **易于扩展**：为优先级调度、资源感知调度奠定基础  
✅ **高可用性**：Leader选举机制保证系统稳定  

**关键设计决策**：
- 使用定时任务扫描PENDING任务
- 按提交时间排序（FIFO）
- Redis分布式锁 + 数据库乐观锁
- 异步提交执行器

这个设计能够支撑 **100 QPS** 的任务调度，满足基本场景需求。后续通过P3-3和P3-4优化，可以达到 **500+ QPS**。
