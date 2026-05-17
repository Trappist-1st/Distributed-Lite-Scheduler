# 工作流并发控制实现指南

> 文档版本：1.0  
> 适用场景：多个实例同时更新同一工作流
> 推荐方案：版本号乐观锁（轻量级）+ 分布式锁（关键路径）

---

## 快速对比

### 方案选择矩阵

| 场景 | 乐观锁 | 悲观锁(分布式锁) | 建议 |
|------|--------|---------|------|
| 冲突频率 | 低 | 高 | 乐观锁 |
| 业务操作 | 轻 | 重 | 乐观锁 |
| 实时性要求 | 宽松 | 严格 | 悲观锁 |
| 吞吐量要求 | 高 | 中 | 乐观锁 |
| 系统复杂度 | 简单 | 复杂 | 乐观锁 |
| **项目现状** | **✓ 推荐** | 可选 | 🎯 |

---

## 方案一：版本号乐观锁（推荐）

### 1. 数据库表设计

```sql
-- 添加版本控制字段
ALTER TABLE workflow 
ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '版本号，每次更新递增';

-- 创建唯一索引加速查询
CREATE INDEX idx_workflow_version ON workflow(id, version);

-- 查看现有数据
SELECT id, workflow_name, version, updated_at FROM workflow LIMIT 10;
```

### 2. MyBatis Plus 实现

#### 方式A：使用 @Version 注解（内置支持）

**实体类修改**：

```java
@Data
@TableName("workflow")
public class Workflow {
    @TableId
    private Long id;
    
    private String workflowName;
    private String dagJson;
    private String description;
    
    // 版本号字段：MyBatis Plus 会自动处理乐观锁
    @Version
    private Integer version;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**Mapper 接口**（无需修改，MyBatis Plus 自动处理）：

```java
@Repository
public interface WorkflowMapper extends BaseMapper<Workflow> {
    // selectById, updateById, delete 等方法会自动加入版本号检查
}
```

**使用方法**：

```java
@Service
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    @Override
    @Transactional
    public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
        // 1. 读取工作流（获取当前版本号）
        Workflow workflow = requireOwnedWorkflow(id);
        Integer originalVersion = workflow.getVersion();
        
        log.info("开始更新工作流 id={} version={}", id, originalVersion);
        
        // 2. 修改业务字段
        updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
        updateDagJson(workflow, request.getDagJson());
        updateSimpleStringField(request.getDescription(), workflow::setDescription);
        
        // 3. 调用 updateById - MyBatis Plus 自动执行：
        //    UPDATE workflow 
        //    SET ... version = version + 1 
        //    WHERE id = ? AND version = ?
        // 
        //    如果版本不匹配（被其他事务修改），返回 0
        int updated = workflowMapper.updateById(workflow);
        
        // 4. 检查更新结果
        if (updated == 0) {
            // 版本号已改变，说明有并发修改
            throw new ConcurrentModificationException(
                "工作流已被其他用户修改，请刷新后重试"
            );
        }
        
        log.info("工作流更新成功 id={} newVersion={}", id, workflow.getVersion() + 1);
        
        // 5. 清除缓存（此时数据库已是最新版本）
        workflowExecutionService.invalidatePlanCache(id);
    }
}
```

#### 方式B：手动实现版本号检查（不使用注解）

**Mapper 自定义方法**：

```java
@Repository
public interface WorkflowMapper extends BaseMapper<Workflow> {
    
    /**
     * 使用版本号进行乐观锁更新
     * 
     * @param workflow 要更新的工作流对象
     * @param expectedVersion 期望的版本号（更新前的版本）
     * @return 更新行数（0 表示版本冲突，1 表示成功）
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
            id = #{workflow.id} 
            AND version = #{expectedVersion}
            AND deleted = 0
    """)
    int updateWithOptimisticLock(
        @Param("workflow") Workflow workflow,
        @Param("expectedVersion") Integer expectedVersion
    );
}
```

**使用这个自定义方法**：

```java
@Override
@Transactional
public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
    Workflow workflow = requireOwnedWorkflow(id);
    Integer originalVersion = workflow.getVersion();
    
    // 修改业务字段
    updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
    updateDagJson(workflow, request.getDagJson());
    
    // 使用版本号进行乐观锁更新
    int updated = workflowMapper.updateWithOptimisticLock(
        workflow,
        originalVersion  // 期望的版本号
    );
    
    if (updated == 0) {
        throw new ConcurrentModificationException(
            "工作流已被其他用户修改，最新版本为 v" + 
            workflowMapper.selectById(id).getVersion()
        );
    }
    
    workflowExecutionService.invalidatePlanCache(id);
}
```

### 3. 异常处理

```java
/**
 * 自定义异常：并发修改
 */
public class ConcurrentModificationException extends RuntimeException {
    public ConcurrentModificationException(String message) {
        super(message);
    }
}

/**
 * 全局异常处理
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentModification(
            ConcurrentModificationException e) {
        log.warn("并发修改异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            Map.of(
                "code", "CONCURRENT_MODIFICATION",
                "message", e.getMessage(),
                "hint", "请刷新页面后重试"
            )
        );
    }
}
```

### 4. 前端重试逻辑

```javascript
// 前端调用更新接口，处理 409 Conflict 响应
async function updateWorkflow(id, data) {
    const maxRetries = 3;
    let retries = 0;
    
    while (retries < maxRetries) {
        try {
            const response = await fetch(`/api/workflows/${id}`, {
                method: 'PUT',
                body: JSON.stringify(data),
                headers: { 'Content-Type': 'application/json' }
            });
            
            if (response.status === 409) {
                // 版本冲突，重新刷新并重试
                console.warn('并发冲突，刷新后重试...');
                await refreshWorkflow(id);  // 重新读取最新版本
                retries++;
                continue;
            }
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            
            return await response.json();
        } catch (error) {
            console.error('更新失败:', error);
            throw error;
        }
    }
    
    throw new Error('超过最大重试次数');
}
```

---

## 方案二：分布式锁（关键路径）

### 使用场景

- 高并发修改同一工作流（需要绝对的一致性）
- 涉及多步骤事务（读 → 检查 → 修改 → 写）
- 调度器和 API 同时操作工作流

### 实现

```java
@Service
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    /**
     * 带分布式锁的更新工作流
     * 
     * 锁策略：
     * - 锁名：workflow:update:{id}
     * - 等待时间：5 秒
     * - 锁持有时间：30 秒
     */
    @Override
    @Transactional
    public void updateWorkflowWithLock(Long id, WorkflowUpdateRequest request) {
        String lockKey = "workflow:update:" + id;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // 尝试获取锁
            boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                    "工作流正在被其他用户修改，请稍后重试"
                );
            }
            
            log.info("获得分布式锁 lockKey={}", lockKey);
            
            // 再次读取，确保获取最新数据
            Workflow workflow = requireOwnedWorkflow(id);
            
            // 执行业务修改
            updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
            updateDagJson(workflow, request.getDagJson());
            
            // 提交更新
            int updated = workflowMapper.updateById(workflow);
            if (updated != 1) {
                throw new IllegalStateException("更新工作流失败");
            }
            
            log.info("工作流更新成功 id={}", id);
            
            // 清除缓存
            workflowExecutionService.invalidatePlanCache(id);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取锁被中断", e);
        } finally {
            // 自动释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("分布式锁已释放 lockKey={}", lockKey);
            }
        }
    }
}
```

### 锁配置优化

```java
/**
 * Redisson 配置优化
 */
@Configuration
public class RedissonConfig {
    
    @Bean
    public Config redissonConfig() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://127.0.0.1:6379")
            // 锁相关配置
            .setLockWatchdogTimeout(30_000)  // 看门狗续期时间（30秒）
            .setConnectionMinimumIdleSize(5)
            .setConnectionPoolSize(10);
        
        return config;
    }
    
    /**
     * 分布式锁的监控和统计
     */
    @Component
    @Slf4j
    public static class LockMetricsCollector {
        
        private final AtomicInteger activeLocks = new AtomicInteger(0);
        private final AtomicInteger totalLockWaits = new AtomicInteger(0);
        
        public void recordLockAcquired(String lockKey) {
            activeLocks.incrementAndGet();
            log.debug("锁已获得 lockKey={} activeLocks={}", lockKey, activeLocks.get());
        }
        
        public void recordLockReleased(String lockKey) {
            activeLocks.decrementAndGet();
            log.debug("锁已释放 lockKey={} activeLocks={}", lockKey, activeLocks.get());
        }
        
        public void recordLockWait(String lockKey) {
            totalLockWaits.incrementAndGet();
            log.debug("等待锁 lockKey={} totalWaits={}", lockKey, totalLockWaits.get());
        }
    }
}
```

---

## 混合方案：乐观锁 + 分布式锁

### 推荐架构

```
普通更新流程           高并发更新流程
    ↓                      ↓
[读数据]              [尝试获取分布式锁]
    ↓                      ↓
[检查版本号]     ← 5秒等待超时
    ↓                      ↓
[修改数据]         [再次读数据（最新版本）]
    ↓                      ↓
[乐观锁更新]           [修改数据]
    ↓                      ↓
    ├─ 成功 ✓        [普通更新（不再需要乐观锁）]
    │                      ↓
    └─ 冲突(0.1%)     [清缓存]
         ↓                 ↓
    重试或报错         [释放分布式锁]
```

### 实现

```java
@Service
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 自适应的并发控制更新
     * 
     * 策略：
     * 1. 首先尝试用乐观锁更新（快速路径）
     * 2. 如果发生版本冲突，则使用分布式锁进行重试
     */
    @Override
    @Transactional
    public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
        try {
            // 快速路径：直接用乐观锁更新
            updateWorkflowWithOptimisticLock(id, request);
            log.info("乐观锁更新成功 id={}", id);
        } catch (ConcurrentModificationException e) {
            // 版本冲突，切换到分布式锁路径
            log.warn("乐观锁冲突，使用分布式锁重试 id={}", id);
            updateWorkflowWithLock(id, request);
        }
    }
    
    private void updateWorkflowWithOptimisticLock(Long id, WorkflowUpdateRequest request) {
        Workflow workflow = requireOwnedWorkflow(id);
        Integer originalVersion = workflow.getVersion();
        
        updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
        updateDagJson(workflow, request.getDagJson());
        
        int updated = workflowMapper.updateById(workflow);
        if (updated == 0) {
            throw new ConcurrentModificationException("版本冲突");
        }
        
        workflowExecutionService.invalidatePlanCache(id);
    }
    
    private void updateWorkflowWithLock(Long id, WorkflowUpdateRequest request) {
        String lockKey = "workflow:update:" + id;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("无法获得分布式锁");
            }
            
            // 重新尝试乐观锁
            updateWorkflowWithOptimisticLock(id, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取锁被中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

---

## 测试用例

```java
@SpringBootTest
@Slf4j
public class WorkflowConcurrencyTest {
    
    @Autowired
    private WorkflowService workflowService;
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    /**
     * 测试并发更新：验证版本号乐观锁
     */
    @Test
    public void testConcurrentUpdateWithOptimisticLock() throws Exception {
        Long workflowId = 123L;
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        List<String> results = new CopyOnWriteArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    WorkflowUpdateRequest request = new WorkflowUpdateRequest();
                    request.setWorkflowName("workflow_v" + index);
                    request.setDescription("Updated by thread " + index);
                    
                    workflowService.updateWorkflow(workflowId, request);
                    results.add("线程 " + index + " 更新成功");
                } catch (ConcurrentModificationException e) {
                    results.add("线程 " + index + " 版本冲突（预期）");
                } catch (Exception e) {
                    results.add("线程 " + index + " 异常: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // 结果分析
        long successCount = results.stream().filter(r -> r.contains("成功")).count();
        long conflictCount = results.stream().filter(r -> r.contains("冲突")).count();
        
        log.info("并发更新测试结果：成功={} 冲突={}", successCount, conflictCount);
        results.forEach(log::info);
        
        // 验证：只有1个线程应该成功，其他应该失败
        assertEquals(1, successCount, "只有1个线程应该更新成功");
        assertEquals(threadCount - 1, conflictCount, "其他线程应该遇到版本冲突");
        
        // 验证最终数据
        Workflow finalWorkflow = workflowMapper.selectById(workflowId);
        assertEquals(threadCount - 1, finalWorkflow.getVersion().intValue(), 
            "版本号应该递增");
    }
    
    /**
     * 测试分布式锁
     */
    @Test
    public void testDistributedLock() throws Exception {
        Long workflowId = 456L;
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        List<Long> executionTimes = new CopyOnWriteArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    WorkflowUpdateRequest request = new WorkflowUpdateRequest();
                    request.setWorkflowName("workflow_locked");
                    
                    workflowService.updateWorkflowWithLock(workflowId, request);
                    
                    long duration = System.currentTimeMillis() - startTime;
                    executionTimes.add(duration);
                    log.info("线程执行耗时: {}ms", duration);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // 验证：所有线程都应该成功（串行执行）
        assertEquals(threadCount, executionTimes.size());
        log.info("分布式锁测试完成，总耗时: {}ms", 
            executionTimes.stream().mapToLong(Long::longValue).sum());
    }
}
```

---

## 部署检查清单

- [ ] 数据库表添加 version 字段
- [ ] Workflow 实体添加 @Version 注解
- [ ] WorkflowMapper 配置 MyBatis Plus 乐观锁拦截器
- [ ] 异常处理器处理 ConcurrentModificationException
- [ ] 前端实现重试逻辑
- [ ] Redis 分布式锁配置正确
- [ ] 日志记录关键操作（获锁、释锁、版本冲突）
- [ ] 监控告警规则配置（并发冲突率、锁等待时间）
- [ ] 测试用例覆盖并发场景
- [ ] 生产环境灰度发布

---

## 参考资源

- [MyBatis Plus 乐观锁](https://baomidou.com/pages/0d93c72b7d212a60/)
- [Redisson 分布式锁](https://redisson.org/features/distributed-locks-and-synchronizers.html)
- [ConcurrentHashMap 源码](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java)
