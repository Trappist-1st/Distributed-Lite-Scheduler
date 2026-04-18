# P3-3: 优先级调度器设计稿

## 1. 文档目标

本文档详细设计优先级调度器，在FIFO调度器基础上增加优先级维度，解决以下问题：

- 如何让重要任务优先执行
- 如何防止低优先级任务饿死
- 如何平衡公平性和效率
- 如何动态调整任务优先级

**核心价值**：让调度器更智能，满足不同业务场景的差异化需求。

---

## 2. 优先级调度原理

### 2.1 为什么需要优先级

**场景1：紧急任务插队**
```
普通数据清洗任务（优先级5）→ 预计等待10分钟
紧急故障修复任务（优先级10）→ 应该立即执行
```

**场景2：业务重要性差异**
```
生产环境任务（优先级10）→ 高优先级
测试环境任务（优先级3）→ 低优先级
开发环境任务（优先级1）→ 最低优先级
```

**场景3：付费用户特权**
```
企业版用户任务（优先级8）→ 优先调度
免费版用户任务（优先级3）→ 普通调度
```

### 2.2 优先级模型

#### 优先级定义
```java
public enum TaskPriority {
    CRITICAL(10, "紧急"),    // 故障修复、生产告警
    HIGH(8, "高"),           // 生产任务、付费用户
    MEDIUM(5, "中"),         // 普通任务
    LOW(3, "低"),            // 测试任务
    MINIMAL(1, "最低");      // 开发任务、批量任务
    
    private final int value;
    private final String description;
}
```

#### 优先级来源
```
任务优先级 = 基础优先级 + 时间加权 + 租户加权

基础优先级：用户提交时指定（1-10）
时间加权：等待时间越长，优先级越高（防饿死）
租户加权：根据租户等级调整（可选）
```

---

## 3. 调度算法

### 3.1 加权优先级算法

**核心公式**：
```
effectivePriority = basePriority × priorityWeight 
                  + waitingMinutes × agingWeight
                  + tenantBonus

其中：
- basePriority: 基础优先级（1-10）
- priorityWeight: 优先级权重（默认10）
- waitingMinutes: 等待时间（分钟）
- agingWeight: 老化权重（默认0.1）
- tenantBonus: 租户加成（0-5）
```

**示例计算**：
```
任务A：优先级10，等待5分钟，企业租户
effectivePriority = 10 × 10 + 5 × 0.1 + 5 = 105.5

任务B：优先级5，等待60分钟，普通租户
effectivePriority = 5 × 10 + 60 × 0.1 + 0 = 56

任务C：优先级3，等待120分钟，普通租户
effectivePriority = 3 × 10 + 120 × 0.1 + 0 = 42

调度顺序：A → B → C
```

### 3.2 防饿死机制（Aging）

**问题**：高优先级任务不断提交，低优先级任务永远得不到执行。

**解决方案**：时间老化（Aging）
```
等待时间越长 → 有效优先级越高 → 最终会被调度

例如：
优先级1的任务，等待1000分钟后：
effectivePriority = 1 × 10 + 1000 × 0.1 = 110
已经超过优先级10的新任务（100）
```

**老化权重调优**：
```
agingWeight = 0.05  → 老化慢，高优先级任务优势明显
agingWeight = 0.1   → 均衡（推荐）
agingWeight = 0.2   → 老化快，更公平但优先级效果弱
```

### 3.3 调度流程

```sql
-- 优先级调度查询
SELECT 
    *,
    (priority * 10 + TIMESTAMPDIFF(MINUTE, submit_time, NOW()) * 0.1) 
        AS effective_priority
FROM task_instance
WHERE status = 'PENDING'
ORDER BY effective_priority DESC  -- 按有效优先级降序
LIMIT 100;
```

---

## 4. 核心实现

### 4.1 数据模型扩展

#### task_instance表增加字段
```sql
ALTER TABLE task_instance 
ADD COLUMN priority INT DEFAULT 5 COMMENT '任务优先级(1-10)';

CREATE INDEX idx_status_priority_submit 
ON task_instance(status, priority DESC, submit_time ASC);
```

#### 租户优先级配置
```sql
ALTER TABLE tenant
ADD COLUMN priority_bonus INT DEFAULT 0 COMMENT '优先级加成(0-5)';
```

### 4.2 调度器实现

```java
@Service
@Slf4j
public class PrioritySchedulerService {
    
    private static final double PRIORITY_WEIGHT = 10.0;
    private static final double AGING_WEIGHT = 0.1;
    
    @Autowired
    private TaskInstanceMapper taskInstanceMapper;
    
    @Autowired
    private TenantMapper tenantMapper;
    
    @Autowired
    private ResourceSlotService resourceSlotService;
    
    @Autowired
    private ResourceQuotaService resourceQuotaService;
    
    /**
     * 调度主循环
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduleLoop() {
        if (!tryAcquireLeadership()) {
            return;
        }
        
        log.info("开始优先级调度周期");
        
        try {
            // 1. 扫描PENDING任务并计算有效优先级
            List<TaskWithPriority> tasks = scanPendingTasksWithPriority();
            
            if (tasks.isEmpty()) {
                return;
            }
            
            log.info("扫描到待调度任务 count={}", tasks.size());
            
            // 2. 按有效优先级排序（已在SQL中完成）
            // 3. 逐个调度
            int successCount = 0;
            
            for (TaskWithPriority taskWithPriority : tasks) {
                boolean scheduled = scheduleTask(taskWithPriority.getTask());
                if (scheduled) {
                    successCount++;
                    
                    log.info("任务调度成功 taskId={} basePriority={} " +
                            "effectivePriority={} waitingMinutes={}", 
                        taskWithPriority.getTask().getId(),
                        taskWithPriority.getTask().getPriority(),
                        taskWithPriority.getEffectivePriority(),
                        taskWithPriority.getWaitingMinutes());
                }
            }
            
            log.info("优先级调度周期完成 success={}", successCount);
            
        } catch (Exception e) {
            log.error("优先级调度周期异常", e);
        }
    }
    
    /**
     * 扫描PENDING任务并计算有效优先级
     */
    private List<TaskWithPriority> scanPendingTasksWithPriority() {
        // 使用数据库计算有效优先级（性能更好）
        return taskInstanceMapper.selectPendingTasksWithPriority(100);
    }
    
    /**
     * 调度单个任务（逻辑与FIFO调度器相同）
     */
    private boolean scheduleTask(TaskInstance task) {
        // 实现与P3-2相同，省略...
        return true;
    }
}
```

### 4.3 Mapper实现

```java
@Mapper
public interface TaskInstanceMapper {
    
    /**
     * 查询PENDING任务并计算有效优先级
     */
    @Select("SELECT " +
            "  ti.*, " +
            "  t.priority_bonus, " +
            "  (ti.priority * #{priorityWeight} + " +
            "   TIMESTAMPDIFF(MINUTE, ti.submit_time, NOW()) * #{agingWeight} + " +
            "   COALESCE(t.priority_bonus, 0)) AS effective_priority, " +
            "  TIMESTAMPDIFF(MINUTE, ti.submit_time, NOW()) AS waiting_minutes " +
            "FROM task_instance ti " +
            "LEFT JOIN project p ON ti.project_id = p.id " +
            "LEFT JOIN tenant t ON p.tenant_id = t.id " +
            "WHERE ti.status = 'PENDING' " +
            "ORDER BY effective_priority DESC " +
            "LIMIT #{limit}")
    List<TaskWithPriority> selectPendingTasksWithPriority(
        @Param("limit") int limit,
        @Param("priorityWeight") double priorityWeight,
        @Param("agingWeight") double agingWeight
    );
}
```

### 4.4 数据模型

```java
@Data
public class TaskWithPriority {
    private TaskInstance task;
    private Double effectivePriority;  // 有效优先级
    private Long waitingMinutes;       // 等待时间（分钟）
    private Integer priorityBonus;     // 租户加成
}
```

---

## 5. 优先级管理

### 5.1 API设计

#### 提交任务时指定优先级
```http
POST /api/task/submit
Content-Type: application/json

{
  "taskName": "紧急数据修复",
  "priority": 10,  // 指定优先级
  ...
}
```

#### 动态调整任务优先级
```http
PATCH /api/task/{taskId}/priority
Content-Type: application/json

{
  "priority": 10,
  "reason": "业务紧急，需要优先执行"
}
```

**实现**：
```java
@PatchMapping("/{taskId}/priority")
public Result<?> updatePriority(
        @PathVariable Long taskId,
        @RequestBody UpdatePriorityRequest request) {
    
    // 权限检查：只有OWNER/ADMIN可以调整优先级
    authorize(getCurrentUserId(), taskId, "task:priority:update");
    
    // 更新优先级
    TaskInstance task = taskInstanceMapper.selectById(taskId);
    
    if (task.getStatus() != TaskStatus.PENDING) {
        throw new IllegalStateException("只能调整PENDING状态的任务优先级");
    }
    
    task.setPriority(request.getPriority());
    taskInstanceMapper.updateById(task);
    
    // 记录审计日志
    auditLog.log("调整任务优先级", taskId, request.getReason());
    
    return Result.success();
}
```

### 5.2 租户优先级配置

```java
@Service
public class TenantService {
    
    /**
     * 设置租户优先级加成
     */
    public void setPriorityBonus(Long tenantId, int bonus) {
        if (bonus < 0 || bonus > 5) {
            throw new IllegalArgumentException("优先级加成范围：0-5");
        }
        
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setPriorityBonus(bonus);
        tenantMapper.updateById(tenant);
        
        log.info("设置租户优先级加成 tenantId={} bonus={}", tenantId, bonus);
    }
}
```

**租户等级与加成映射**：
```
免费版：bonus = 0
基础版：bonus = 1
专业版：bonus = 3
企业版：bonus = 5
```

---

## 6. 优先级策略

### 6.1 严格优先级 vs 公平优先级

#### 严格优先级（agingWeight = 0）
```
优点：高优先级任务响应快
缺点：低优先级任务可能饿死

适用场景：生产环境，优先级差异明显
```

#### 公平优先级（agingWeight = 0.2）
```
优点：所有任务最终都会执行
缺点：优先级效果不明显

适用场景：开发/测试环境，公平性要求高
```

#### 均衡优先级（agingWeight = 0.1，推荐）
```
优点：兼顾效率和公平
缺点：需要调优

适用场景：大多数生产环境
```

### 6.2 优先级抢占（可选增强）

**场景**：紧急任务提交时，是否可以抢占正在运行的低优先级任务？

```java
public class PreemptiveScheduler {
    
    /**
     * 抢占式调度
     */
    public boolean scheduleWithPreemption(TaskInstance urgentTask) {
        // 1. 尝试正常调度
        if (scheduleTask(urgentTask)) {
            return true;
        }
        
        // 2. 无可用资源，尝试抢占
        if (urgentTask.getPriority() >= 9) { // 只有高优先级才能抢占
            TaskInstance victim = findVictimTask(urgentTask);
            
            if (victim != null) {
                // 暂停低优先级任务
                pauseTask(victim);
                
                // 调度高优先级任务
                return scheduleTask(urgentTask);
            }
        }
        
        return false;
    }
    
    /**
     * 选择被抢占的任务
     */
    private TaskInstance findVictimTask(TaskInstance urgentTask) {
        // 查找优先级低且可暂停的任务
        return taskInstanceMapper.selectRunningTasks().stream()
            .filter(t -> t.getPriority() < urgentTask.getPriority() - 3)
            .filter(t -> t.isPreemptible()) // 任务标记为可抢占
            .min(Comparator.comparing(TaskInstance::getPriority))
            .orElse(null);
    }
}
```

---

## 7. 监控与分析

### 7.1 优先级分布统计

```sql
-- 各优先级任务数量分布
SELECT 
    priority,
    COUNT(*) as task_count,
    AVG(TIMESTAMPDIFF(MINUTE, submit_time, start_time)) as avg_wait_minutes
FROM task_instance
WHERE status IN ('RUNNING', 'SUCCESS', 'FAILED')
  AND start_time IS NOT NULL
GROUP BY priority
ORDER BY priority DESC;
```

**输出示例**：
```
priority | task_count | avg_wait_minutes
---------|------------|------------------
   10    |    120     |      0.5
    8    |    350     |      2.3
    5    |   1200     |      8.7
    3    |    800     |     15.2
    1    |    200     |     45.6
```

### 7.2 饿死检测

```sql
-- 检测等待时间超过1小时的低优先级任务
SELECT 
    id,
    task_name,
    priority,
    TIMESTAMPDIFF(MINUTE, submit_time, NOW()) as waiting_minutes
FROM task_instance
WHERE status = 'PENDING'
  AND priority <= 3
  AND TIMESTAMPDIFF(MINUTE, submit_time, NOW()) > 60
ORDER BY waiting_minutes DESC;
```

### 7.3 Grafana面板

```
┌─────────────────────────────────────────────────────┐
│  优先级调度监控                                      │
├─────────────────────────────────────────────────────┤
│  各优先级任务数：                                    │
│    P10: ████ 120                                    │
│    P8:  ████████ 350                                │
│    P5:  ████████████████████ 1200                   │
│    P3:  ████████████ 800                            │
│    P1:  ████ 200                                    │
│                                                     │
│  平均等待时间：                                      │
│    P10: 0.5min                                      │
│    P8:  2.3min                                      │
│    P5:  8.7min                                      │
│    P3:  15.2min                                     │
│    P1:  45.6min ⚠️ 告警                             │
└─────────────────────────────────────────────────────┘
```

---

## 8. 性能优化

### 8.1 索引优化

```sql
-- 复合索引（覆盖优先级查询）
CREATE INDEX idx_status_priority_submit 
ON task_instance(status, priority DESC, submit_time ASC);

-- 分析查询计划
EXPLAIN SELECT * FROM task_instance
WHERE status = 'PENDING'
ORDER BY priority DESC, submit_time ASC
LIMIT 100;
```

### 8.2 缓存热点租户加成

```java
@Service
public class TenantPriorityCache {
    
    @Cacheable(value = "tenant:priority", key = "#tenantId")
    public int getPriorityBonus(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        return tenant.getPriorityBonus();
    }
    
    @CacheEvict(value = "tenant:priority", key = "#tenantId")
    public void evictCache(Long tenantId) {
        // 租户配置变更时清除缓存
    }
}
```

### 8.3 分优先级队列（可选）

```java
public class MultiQueueScheduler {
    
    private PriorityQueue<TaskInstance> highPriorityQueue;   // P8-10
    private PriorityQueue<TaskInstance> mediumPriorityQueue; // P4-7
    private PriorityQueue<TaskInstance> lowPriorityQueue;    // P1-3
    
    /**
     * 加权轮询调度
     */
    public TaskInstance nextTask() {
        // 高:中:低 = 5:3:2 的比例调度
        for (int i = 0; i < 5; i++) {
            TaskInstance task = highPriorityQueue.poll();
            if (task != null) return task;
        }
        
        for (int i = 0; i < 3; i++) {
            TaskInstance task = mediumPriorityQueue.poll();
            if (task != null) return task;
        }
        
        for (int i = 0; i < 2; i++) {
            TaskInstance task = lowPriorityQueue.poll();
            if (task != null) return task;
        }
        
        return null;
    }
}
```

---

## 9. 测试方案

### 9.1 优先级正确性测试

```java
@Test
public void testPriorityOrder() {
    // 创建不同优先级的任务
    TaskInstance p10 = createTask(10); // 高优先级
    TaskInstance p5 = createTask(5);   // 中优先级
    TaskInstance p1 = createTask(1);   // 低优先级
    
    // 执行调度
    schedulerService.scheduleLoop();
    
    // 验证调度顺序
    List<TaskInstance> scheduled = getScheduledTasks();
    assertEquals(p10.getId(), scheduled.get(0).getId());
    assertEquals(p5.getId(), scheduled.get(1).getId());
    assertEquals(p1.getId(), scheduled.get(2).getId());
}
```

### 9.2 防饿死测试

```java
@Test
public void testAgingPreventsStarvation() {
    // 创建低优先级任务（提交时间早）
    TaskInstance oldLowPriority = createTask(1);
    Thread.sleep(60000); // 等待60分钟（模拟）
    
    // 创建高优先级任务（提交时间晚）
    TaskInstance newHighPriority = createTask(10);
    
    // 计算有效优先级
    double oldEffective = 1 * 10 + 60 * 0.1; // 16
    double newEffective = 10 * 10 + 0 * 0.1; // 100
    
    // 高优先级仍然优先
    assertTrue(newEffective > oldEffective);
    
    // 但如果低优先级等待1000分钟
    double veryOldEffective = 1 * 10 + 1000 * 0.1; // 110
    
    // 最终会超过高优先级
    assertTrue(veryOldEffective > newEffective);
}
```

### 9.3 性能测试

```java
@Test
public void testSchedulerPerformance() {
    // 创建10000个不同优先级的任务
    for (int i = 0; i < 10000; i++) {
        int priority = (i % 10) + 1;
        createTask(priority);
    }
    
    // 测试调度性能
    long start = System.currentTimeMillis();
    schedulerService.scheduleLoop();
    long elapsed = System.currentTimeMillis() - start;
    
    // 验证性能：100个任务应在1秒内完成调度
    assertTrue(elapsed < 1000);
}
```

---

## 10. 常见问题

### Q1: 如何防止用户滥用高优先级？

**答**：
1. 权限控制：只有OWNER/ADMIN可以设置P8以上优先级
2. 配额限制：每个租户每天最多10个P10任务
3. 审计日志：记录所有优先级调整操作
4. 自动降级：P10任务执行后自动降为P8

### Q2: 优先级和租户配额冲突怎么办？

**答**：配额优先。即使是P10任务,如果租户配额不足，也不会被调度。

### Q3: 如何调优老化权重？

**答**：
1. 观察低优先级任务的平均等待时间
2. 如果等待时间过长（>1小时），增大agingWeight
3. 如果高优先级任务响应慢，减小agingWeight
4. 推荐从0.1开始，逐步调整

---

## 11. 与FIFO调度器对比

| 维度 | FIFO调度器 | 优先级调度器 |
|-----|-----------|-------------|
| 调度依据 | 提交时间 | 优先级 + 提交时间 |
| 公平性 | 完全公平 | 按优先级公平 |
| 响应速度 | 一致 | 高优先级快 |
| 复杂度 | 简单 | 中等 |
| 适用场景 | 测试环境 | 生产环境 |
| 防饿死 | 天然支持 | 需要Aging机制 |

---

## 12. 后续优化方向

### 12.1 P3-4: 资源感知调度

在优先级基础上增加资源匹配度：

```java
score = effectivePriority * 0.7 + resourceFitScore * 0.3
```

### 12.2 动态优先级

根据任务执行历史动态调整：

```java
// 经常失败的任务降低优先级
// 执行时间短的任务提高优先级
```

### 12.3 多级反馈队列

参考操作系统调度算法：

```
新任务 → 高优先级队列
执行时间长 → 降级到中优先级队列
继续执行 → 降级到低优先级队列
```

---

## 13. 总结

优先级调度器是调度引擎的**智能升级**，核心价值：

✅ **差异化服务**：重要任务优先执行，提升用户体验  
✅ **防止饿死**：Aging机制确保所有任务最终执行  
✅ **灵活配置**：支持任务级和租户级优先级  
✅ **平滑升级**：在FIFO基础上扩展，兼容性好  

**关键设计决策**：
- 有效优先级 = 基础优先级 × 10 + 等待时间 × 0.1
- 老化权重默认0.1（可调）
- 支持动态调整优先级
- 租户优先级加成0-5

这个设计能够满足 **90%** 的生产环境需求，是调度器走向成熟的关键一步。
