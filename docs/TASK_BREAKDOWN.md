# 项目任务清单 (Task Breakdown)

## 🎯 Phase 0: 项目准备与设计（3-5天）

### 📋 P0-1: 需求分析与建模
**描述**：完成完整的数据库设计，包括ER图、关系模式、规范化分析
**产出**：
- [ ] ER图（使用draw.io/Lucidchart）
- [ ] 关系模式文档
- [ ] 第三范式分析文档
- [ ] 数据字典（每个实体的字段说明）

### 📋 P0-2: 技术选型与环境搭建
**描述**：确定技术栈，搭建开发环境
**产出**：
- [ ] pom.xml配置（Spring Boot 3.2+）
- [ ] Docker Compose配置（MySQL + Redis）
- [ ] 项目目录结构
- [ ] Git仓库初始化

### 📋 P0-3: 数据库DDL编写
**描述**：将ER图转换为可执行的SQL DDL
**产出**：
- [ ] schema.sql（12+表定义）
- [ ] init-data.sql（初始化数据）
- [ ] 索引设计SQL（10+索引）
- [ ] Liquibase changelog（可选）

**依赖**：P0-1

---

## 🧱 Phase 1: 基础功能开发（5-7天）

### 📋 P1-1: 用户与租户管理
**描述**：实现用户注册、登录、租户管理
**核心表**：`user`, `tenant`, `tenant_member`
**API**：
- [ ] POST /api/auth/register
- [ ] POST /api/auth/login
- [ ] GET /api/tenant/list
- [ ] POST /api/tenant/create

### 📋 P1-2: 项目与任务CRUD
**描述**：实现项目和任务的增删改查
**核心表**：`project`, `task`
**API**：
- [ ] 项目CRUD（4个接口）
- [ ] 任务CRUD（4个接口）
- [ ] 批量创建任务接口
**关键逻辑**：
- [ ] 参数校验（JSR303）
- [ ] 软删除实现

### 📋 P1-3: 任务状态机
**描述**：实现任务状态流转逻辑
**状态**：PENDING → RUNNING → SUCCESS/FAILED
**关键代码**：
```java
enum TaskStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}

// 状态转换校验
boolean canTransition(TaskStatus from, TaskStatus to);
```
**测试**：
- [ ] 单元测试（状态转换矩阵）
- [ ] 非法转换抛异常

**依赖**：P1-2

### 📋 P1-4: 执行日志记录
**描述**：实现任务执行日志的记录与查询
**核心表**：`execution_log`
**API**：
- [ ] GET /api/task/{id}/logs
- [ ] POST /api/task/log（内部接口）
**优化**：
- [ ] 日志分页查询
- [ ] 按时间分区（可选）

---

## 🚀 Phase 2: 资源管理模块（4-6天）

### 📋 P2-1: 资源节点管理
**描述**：实现Worker节点的注册、注销、健康检查
**核心表**：`resource_node`
**功能**：
- [ ] Worker节点注册（POST /api/resource/register）
- [ ] 节点心跳上报（POST /api/resource/heartbeat）
- [ ] 节点状态查询（GET /api/resource/nodes）
- [ ] 自动注销（超时未心跳）

### 📋 P2-2: 资源槽位管理
**描述**：实现CPU/GPU资源的抽象与分配
**核心表**：`resource_slot`, `resource_usage`
**关键逻辑**：
```java
class ResourceSlot {
    Long nodeId;
    ResourceType type; // CPU, GPU, MEMORY
    int total;
    int available;
}
```
- [ ] 资源预留接口
- [ ] 资源释放接口
- [ ] 资源使用记录

**依赖**：P2-1

### 📋 P2-3: 租户资源配额
**描述**：实现多租户资源隔离与配额管理
**核心表**：`resource_quota`
**功能**：
- [ ] 配额设置接口
- [ ] 配额检查逻辑
- [ ] 超额拒绝策略
**关键SQL**：
```sql
SELECT SUM(cpu_used) as total_used
FROM resource_usage
WHERE tenant_id = ? AND status = 'RUNNING';
```

**依赖**：P2-2

---

## 🧠 Phase 3: 调度引擎核心（7-10天）

### 📋 P3-1: 任务提交削峰队列
**描述**：实现高并发任务提交的削峰机制
**技术**：BlockingQueue + 批量插入
**关键代码**：
```java
@Service
class TaskSubmitService {
    private BlockingQueue<Task> submitQueue;
    
    @Scheduled(fixedDelay = 1000)
    void batchInsert() {
        List<Task> batch = new ArrayList<>();
        submitQueue.drainTo(batch, 100);
        if (!batch.isEmpty()) {
            taskMapper.batchInsert(batch);
        }
    }
}
```
**测试**：
- [ ] JMeter压测（1000 QPS）
- [ ] 队列溢出测试

### 📋 P3-2: 简单FIFO调度器
**描述**：实现最基础的先进先出调度
**功能**：
- [ ] 定时扫描PENDING任务
- [ ] 分配资源
- [ ] 更新状态为RUNNING
- [ ] 提交到执行器
**关键SQL**：
```sql
SELECT * FROM task_instance
WHERE status = 'PENDING'
ORDER BY create_time ASC
LIMIT 100;
```

**依赖**：P3-1, P2-2

### 📋 P3-3: 优先级调度器
**描述**：实现基于优先级的调度算法
**核心**：
- [ ] 优先级字段（HIGH/MEDIUM/LOW）
- [ ] 优先级堆（PriorityQueue）
- [ ] 防饥饿机制（时间加权）
**算法**：
```java
score = priority_weight * 10 + wait_time_minutes / 10
```

**依赖**：P3-2

### 📋 P3-4: 资源感知调度
**描述**：实现Best Fit资源分配算法
**功能**：
- [ ] 资源需求声明（Task.resourceRequirement）
- [ ] Best Fit匹配逻辑
- [ ] 资源碎片统计
**伪代码**：
```
for each task in queue:
    best_node = find_best_fit_node(task.resource_req)
    if best_node:
        allocate(task, best_node)
```

**依赖**：P3-3, P2-2

### 📋 P3-5: 并发安全保障
**描述**：实现任务分配的并发控制
**技术方案**：
- [ ] 数据库乐观锁（version字段）
- [ ] Redis分布式锁（Redisson）
- [ ] 幂等性设计

**Redis分布式锁实现细节**：

#### 1. Redisson配置
```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setPassword(password)
            .setConnectionPoolSize(64)
            .setConnectionMinimumIdleSize(10);
        
        // Watch Dog超时时间：30秒
        config.setLockWatchdogTimeout(30000L);
        
        return Redisson.create(config);
    }
}
```

#### 2. Leader选举锁
```java
@Component
public class SchedulerLeaderElection {
    private static final String LEADER_LOCK_KEY = "scheduler:leader:lock";
    
    @Autowired
    private RedissonClient redisson;
    
    private volatile boolean isLeader = false;
    
    @Scheduled(fixedRate = 10000) // 每10秒检查一次
    public void tryAcquireLeadership() {
        RLock lock = redisson.getLock(LEADER_LOCK_KEY);
        try {
            boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (acquired && !isLeader) {
                isLeader = true;
                log.info("成为Scheduler Leader");
                startScheduling();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

#### 3. 任务调度锁
```java
public boolean scheduleTask(Long taskId) {
    String lockKey = "task:schedule:lock:" + taskId;
    RLock lock = redisson.getLock(lockKey);
    
    try {
        // 尝试加锁，最多等待3秒，锁10秒后自动释放
        boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
        if (!acquired) {
            log.warn("任务{}正在被其他实例调度", taskId);
            return false;
        }
        
        // 双重检查任务状态
        TaskInstance task = taskMapper.selectById(taskId);
        if (task.getStatus() != TaskStatus.PENDING) {
            return false;
        }
        
        // 执行调度逻辑
        return doSchedule(task);
        
    } catch (InterruptedException e) {
        return false;
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

#### 4. 资源分配锁
```java
public ResourceAllocation allocate(TaskInstance task) {
    List<ResourceNode> nodes = resourceNodeMapper.selectAvailable();
    
    for (ResourceNode node : nodes) {
        String lockKey = "resource:node:lock:" + node.getId();
        RLock lock = redisson.getLock(lockKey);
        
        try {
            if (!lock.tryLock(0, 5, TimeUnit.SECONDS)) {
                continue; // 节点被占用，尝试下一个
            }
            
            // 再次检查资源充足性
            node = resourceNodeMapper.selectById(node.getId());
            if (node.canFit(task.getResourceRequirement())) {
                return doAllocate(task, node);
            }
            
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    return null;
}
```

#### 5. 数据库乐观锁（辅助）
```java
@Transactional
int allocateTask(Long taskId, Long nodeId, int version) {
    // 更新任务状态（带版本号）
    return taskMapper.updateStatusWithVersion(
        taskId, PENDING, RUNNING, version
    );
}
```

**测试用例**：
- [ ] 并发调度同一任务（100线程，验证只有1个成功）
- [ ] Leader选举测试（启动3个实例，验证只有1个Leader）
- [ ] 资源分配并发测试（10个任务竞争1个节点）
- [ ] 锁超时自动释放测试
- [ ] Watch Dog续期测试（执行时间超过30秒）

**依赖**：P3-4

---

## 🔗 Phase 4: DAG工作流引擎（5-7天）

### 📋 P4-1: 工作流定义与解析
**描述**：实现工作流的创建、存储、解析
**核心表**：`workflow`, `task_dependency`
**功能**：
- [ ] 工作流CRUD接口
- [ ] DAG JSON解析
- [ ] 循环依赖检测
**JSON格式**：
```json
{
  "tasks": ["A", "B", "C", "D"],
  "dependencies": [
    {"from": "A", "to": "B"},
    {"from": "B", "to": "D"}
  ]
}
```

### 📋 P4-2: 拓扑排序引擎
**描述**：实现DAG的拓扑排序
**算法**：Kahn算法（入度表）
**关键逻辑**：
```java
List<List<Task>> topologicalSort(Map<Long, Set<Long>> graph) {
    // 返回分层结构，每层可并行执行
}
```
**测试**：
- [ ] 单元测试（多种DAG结构）
- [ ] 循环检测测试

**依赖**：P4-1

### 📋 P4-3: 工作流实例执行
**描述**：实现工作流的并行执行
**核心表**：`workflow_instance`
**功能**：
- [ ] 工作流实例化
- [ ] 按层并行执行
- [ ] 失败处理（继续 vs 终止）
- [ ] 实例状态跟踪
**状态机**：
```
PENDING → RUNNING → SUCCESS/FAILED/PARTIAL_SUCCESS
```

**依赖**：P4-2, P3-5

### 📋 P4-4: 条件分支支持（进阶）
**描述**：实现基于条件的任务执行
**功能**：
- [ ] 条件表达式解析（SpEL）
- [ ] 动态路由
**示例**：
```yaml
task_c:
  condition: "${task_a.result == 'success'}"
  depends_on: [task_a]
```

**依赖**：P4-3

---

## 🏃 Phase 5: 执行器实现（4-5天）

### 📋 P5-1: Shell执行器
**描述**：实现Shell脚本执行
**功能**：
- [ ] 执行Shell命令
- [ ] 捕获stdout/stderr
- [ ] 超时控制
- [ ] 进程管理
**关键代码**：
```java
ProcessBuilder pb = new ProcessBuilder("bash", "-c", script);
Process process = pb.start();
int exitCode = process.waitFor(timeout, TimeUnit.SECONDS);
```

### 📋 P5-2: Python执行器
**描述**：支持Python脚本执行
**功能**：
- [ ] 虚拟环境管理
- [ ] 依赖安装
- [ ] 脚本执行
**执行方式**：
```bash
python3 -m venv /tmp/venv_${task_id}
source /tmp/venv_${task_id}/bin/activate
pip install -r requirements.txt
python script.py
```

**依赖**：P5-1

### 📋 P5-3: Docker执行器（进阶）
**描述**：支持Docker容器执行
**功能**：
- [ ] Docker镜像拉取
- [ ] 容器启动
- [ ] 日志收集
- [ ] 资源限制
**Docker命令**：
```bash
docker run --cpus=2 --memory=4g \
  -v /data:/data \
  my-image:latest \
  python train.py
```

**依赖**：P5-2

### 📋 P5-4: 任务重试机制
**描述**：实现任务失败重试
**策略**：
- [ ] 最大重试次数
- [ ] 指数退避
- [ ] 重试条件（仅特定错误码）
**配置**：
```java
@RetryableTask(
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
```

**依赖**：P5-1, P5-2

---

## 📊 Phase 6: 复杂查询与统计（3-4天）

### 📋 P6-1: 任务统计查询
**描述**：实现复杂的统计分析查询
**查询列表**：
- [ ] Q1: 项目任务成功率统计
- [ ] Q2: 资源节点利用率统计
- [ ] Q3: 租户资源使用排行
- [ ] Q4: 任务平均执行时长
- [ ] Q5: 高峰时段任务分布
**示例SQL（Q1）**：
```sql
SELECT 
    p.name,
    COUNT(*) as total_tasks,
    SUM(CASE WHEN ti.status='SUCCESS' THEN 1 ELSE 0 END) as success_count,
    AVG(ti.duration) as avg_duration
FROM task_instance ti
JOIN task t ON ti.task_id = t.id
JOIN project p ON t.project_id = p.id
WHERE ti.create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY p.id
HAVING total_tasks > 10;
```

### 📋 P6-2: 视图设计
**描述**：创建常用查询视图
**视图列表**：
- [ ] V1: 任务执行概览视图
- [ ] V2: 资源使用实时视图
- [ ] V3: 工作流执行历史视图
- [ ] V4: 告警事件视图
- [ ] V5: 租户配额使用视图

### 📋 P6-3: 存储过程（可选）
**描述**：实现复杂业务逻辑的存储过程
**功能**：
- [ ] SP1: 批量任务状态更新
- [ ] SP2: 资源自动回收
- [ ] SP3: 历史数据归档

**依赖**：P6-1

---

## 🔍 Phase 7: 性能优化（3-5天）

### 📋 P7-1: 索引优化实验
**描述**：对比索引前后的查询性能
**实验**：
- [ ] 准备10万+测试数据
- [ ] 记录无索引查询时间
- [ ] 添加索引
- [ ] 记录有索引查询时间
- [ ] EXPLAIN分析
**产出**：性能对比报告（表格+图表）

### 📋 P7-2: SQL查询优化
**描述**：优化慢查询
**优化点**：
- [ ] 避免SELECT *
- [ ] 使用LIMIT
- [ ] JOIN优化（小表驱动大表）
- [ ] 子查询改写为JOIN
**工具**：
- [ ] MySQL慢查询日志
- [ ] EXPLAIN ANALYZE

**依赖**：P7-1

### 📋 P7-3: 缓存引入
**描述**：引入Redis缓存热点数据，提升查询性能，降低数据库压力

#### 缓存架构设计
```
请求 → L1(Caffeine本地缓存) → L2(Redis缓存) → L3(MySQL数据库)
        ↓ 5min TTL             ↓ 30min TTL      ↓ 持久化
```

#### 1. Redis配置
```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用Jackson序列化
        Jackson2JsonRedisSerializer<Object> serializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        
        return template;
    }
}
```

#### 2. 缓存工具类
```java
@Component
public class RedisCacheService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    public <T> void set(String key, T value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }
    
    public <T> T get(String key, Class<T> clazz) {
        return (T) redisTemplate.opsForValue().get(key);
    }
    
    public void delete(String key) {
        redisTemplate.delete(key);
    }
    
    public void deletePattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
```

#### 3. Cache-Aside实现
```java
@Service
public class UserService {
    private static final String USER_CACHE_PREFIX = "user:info:";
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private RedisCacheService cacheService;
    
    /**
     * 查询用户（带缓存）
     */
    public User getUserById(Long userId) {
        String cacheKey = USER_CACHE_PREFIX + userId;
        
        // 1. 尝试从缓存获取
        User user = cacheService.get(cacheKey, User.class);
        if (user != null) {
            return user;
        }
        
        // 2. 查询数据库
        user = userMapper.selectById(userId);
        if (user == null) {
            // 防止缓存穿透：缓存空对象
            cacheService.set(cacheKey, new User(), 5, TimeUnit.MINUTES);
            return null;
        }
        
        // 3. 写入缓存
        cacheService.set(cacheKey, user, 30, TimeUnit.MINUTES);
        return user;
    }
    
    /**
     * 更新用户（删除缓存）
     */
    @Transactional
    public void updateUser(User user) {
        userMapper.updateById(user);
        cacheService.delete(USER_CACHE_PREFIX + user.getId());
    }
}
```

#### 4. 缓存对象与TTL
| 缓存对象 | Key格式 | TTL | 说明 |
|---------|---------|-----|------|
| 用户信息 | `user:info:{userId}` | 30min | 变更不频繁 |
| 项目配置 | `project:config:{projectId}` | 10min | 中等频率更新 |
| 资源节点列表 | `resource:nodes:all` | 1min | 实时性要求高 |
| 任务统计 | `task:stats:{projectId}:{date}` | 5min | 定期刷新 |
| 工作流DAG | `workflow:dag:{workflowId}` | 1hour | 很少变更 |

#### 5. 缓存穿透防护（布隆过滤器）
```java
@Service
public class TaskService {
    @Autowired
    private RedissonClient redisson;
    
    private RBloomFilter<Long> taskIdFilter;
    
    @PostConstruct
    public void init() {
        taskIdFilter = redisson.getBloomFilter("task:id:filter");
        // 预期10万个元素，误判率0.01
        taskIdFilter.tryInit(100000L, 0.01);
        
        // 加载所有任务ID
        List<Long> taskIds = taskMapper.selectAllIds();
        taskIds.forEach(taskIdFilter::add);
    }
    
    public Task getTaskById(Long taskId) {
        // 先检查布隆过滤器
        if (!taskIdFilter.contains(taskId)) {
            return null; // 肯定不存在
        }
        
        // 查询缓存和数据库
        return getFromCacheOrDB(taskId);
    }
}
```

#### 6. 缓存雪崩防护（随机TTL）
```java
public void setCacheWithRandomTTL(String key, Object value, long baseTTL) {
    Random random = new Random();
    // 在基础TTL上增加±20%的随机时间
    long randomOffset = (long) (baseTTL * 0.2 * (random.nextDouble() - 0.5) * 2);
    long finalTTL = baseTTL + randomOffset;
    
    cacheService.set(key, value, finalTTL, TimeUnit.SECONDS);
}
```

#### 7. 缓存击穿防护（热点数据）
```java
public Data getHotData(String key) {
    String cacheKey = "hot:data:" + key;
    
    // 1. 查缓存
    Data data = cacheService.get(cacheKey, Data.class);
    if (data != null) {
        return data;
    }
    
    // 2. 加分布式锁
    String lockKey = "hot:data:lock:" + key;
    RLock lock = redisson.getLock(lockKey);
    
    try {
        lock.lock();
        
        // 3. 双重检查
        data = cacheService.get(cacheKey, Data.class);
        if (data != null) {
            return data;
        }
        
        // 4. 查数据库
        data = dataMapper.selectByKey(key);
        if (data != null) {
            cacheService.set(cacheKey, data, 60, TimeUnit.MINUTES);
        }
        
        return data;
    } finally {
        lock.unlock();
    }
}
```

#### 8. 缓存预热
```java
@Component
public class CacheWarmer {
    @PostConstruct
    public void warmUp() {
        log.info("开始缓存预热...");
        
        // 加载活跃项目
        List<Project> projects = projectMapper.selectActiveProjects();
        projects.forEach(p -> {
            String key = "project:config:" + p.getId();
            cacheService.set(key, p, 10, TimeUnit.MINUTES);
        });
        
        // 加载资源节点
        List<ResourceNode> nodes = resourceNodeMapper.selectAll();
        cacheService.set("resource:nodes:all", nodes, 1, TimeUnit.MINUTES);
        
        log.info("缓存预热完成");
    }
}
```

#### 9. 缓存监控
```java
@Component
public class CacheMetrics {
    @Autowired
    private MeterRegistry registry;
    
    private Counter cacheHit;
    private Counter cacheMiss;
    
    @PostConstruct
    public void init() {
        cacheHit = Counter.builder("cache.hit").register(registry);
        cacheMiss = Counter.builder("cache.miss").register(registry);
    }
    
    public void recordHit() {
        cacheHit.increment();
    }
    
    public void recordMiss() {
        cacheMiss.increment();
    }
}
```

**性能目标**：
- [ ] 缓存命中率 > 80%
- [ ] Redis响应时间P99 < 10ms
- [ ] 数据库查询压力降低70%

**测试用例**：
- [ ] 缓存命中/未命中测试
- [ ] 缓存过期自动刷新测试
- [ ] 布隆过滤器误判率测试
- [ ] 热点数据并发查询测试（1000 QPS）
- [ ] 缓存预热性能测试

**依赖**：P7-2

### 📋 P7-4: 批量操作优化
**描述**：优化批量插入/更新性能
**技术**：
- [ ] JDBC Batch
- [ ] MyBatis批量插入
- [ ] 批量大小调优（100-1000）
**测试**：
- [ ] 单次插入 vs 批量插入（性能对比）

---

## 📈 Phase 8: 监控与告警（4-5天）

### 📋 P8-1: 指标采集
**描述**：实现系统指标的采集
**指标**：
- [ ] JVM指标（Micrometer）
- [ ] 业务指标（任务数、成功率）
- [ ] 资源指标（CPU、内存使用）
**技术**：
- [ ] Spring Boot Actuator
- [ ] Prometheus Exporter

### 📋 P8-2: Grafana面板
**描述**：创建监控可视化面板
**面板**：
- [ ] 系统概览（QPS、延迟）
- [ ] 任务执行趋势
- [ ] 资源使用率
- [ ] 告警事件列表

**依赖**：P8-1

### 📋 P8-3: 告警规则
**描述**：实现基于规则的告警
**核心表**：`alert_rule`, `alert_event`
**规则示例**：
- [ ] 任务失败率 > 10%
- [ ] 队列积压 > 1000
- [ ] 资源使用率 > 90%
**通知渠道**：
- [ ] 邮件（JavaMail）
- [ ] 企业微信/钉钉（Webhook）

**依赖**：P8-2

---

## 🖥️ Phase 9: 前端界面（5-7天，可选）

### 📋 P9-1: 项目搭建与路由
**描述**：Vue/React项目初始化
**功能**：
- [ ] 项目脚手架
- [ ] 路由配置
- [ ] Axios封装
- [ ] 登录鉴权

### 📋 P9-2: 任务管理页面
**描述**：任务的增删改查界面
**功能**：
- [ ] 任务列表（分页、筛选）
- [ ] 创建任务表单
- [ ] 任务详情页
- [ ] 日志查看

**依赖**：P9-1

### 📋 P9-3: 工作流编排器
**描述**：可视化DAG编排工具
**技术**：
- [ ] G6图编辑器
- [ ] 拖拽创建节点
- [ ] 连线表示依赖
- [ ] 保存为JSON
**参考**：Apache Airflow UI

**依赖**：P9-2

### 📋 P9-4: 实时监控大屏
**描述**：实时数据可视化
**功能**：
- [ ] ECharts仪表盘
- [ ] WebSocket实时推送
- [ ] 资源热力图
**效果**：类似Grafana

**依赖**：P9-3

---

## 🧪 Phase 10: 测试与交付（3-4天）

### 📋 P10-1: 单元测试
**描述**：核心逻辑单元测试
**覆盖率目标**：>60%
**测试框架**：JUnit 5 + Mockito
**测试重点**：
- [ ] 状态机转换
- [ ] 资源分配算法
- [ ] DAG拓扑排序
- [ ] 并发安全

### 📋 P10-2: 集成测试
**描述**：端到端测试
**工具**：Spring Boot Test + Testcontainers
**场景**：
- [ ] 任务提交→调度→执行→完成
- [ ] 工作流执行
- [ ] 并发提交

**依赖**：P10-1

### 📋 P10-3: 压力测试
**描述**：系统性能测试
**工具**：JMeter / Gatling
**场景**：
- [ ] 1000 QPS任务提交
- [ ] 10000并发任务执行
- [ ] 长时间稳定性测试
**产出**：性能测试报告

**依赖**：P10-2

### 📋 P10-4: 文档编写
**描述**：完善项目文档
**文档清单**：
- [ ] README.md（项目介绍、快速开始）
- [ ] ARCHITECTURE.md（架构设计）
- [ ] API.md（接口文档）
- [ ] DEPLOYMENT.md（部署指南）
- [ ] DATABASE.md（数据库设计文档）
- [ ] 作业报告（ER图、SQL、实验结果）

---

## 🎯 关键里程碑

| 里程碑 | 完成标志 | 预计时间 |
|--------|----------|----------|
| M1: 数据库设计完成 | ER图 + DDL | Day 5 |
| M2: 基础CRUD完成 | 可创建任务并查询 | Day 12 |
| M3: 调度器可用 | 任务能自动调度执行 | Day 22 |
| M4: DAG工作流可用 | 依赖任务正确执行 | Day 29 |
| M5: 性能优化完成 | 达到性能目标 | Day 34 |
| M6: 项目交付 | 所有文档完成 | Day 36 |

---

## 📊 工作量评估

**总开发时间**：30-40天（全职）/ 50-70天（兼职）

**难度分布**：
- 简单（20%）：CRUD、基础查询
- 中等（50%）：调度器、资源管理、工作流
- 困难（30%）：并发控制、性能优化、分布式一致性

**人员要求**：
- 熟悉Spring Boot + MyBatis
- 了解多线程编程
- 了解基本算法（图、堆）
- 有SQL优化经验更佳

---

## 🔧 风险与应对

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| 并发bug难以复现 | 中 | 高 | 增加单元测试，引入并发测试框架 |
| 性能达不到目标 | 中 | 中 | 提前做性能测试，留出优化时间 |
| DAG算法理解困难 | 低 | 中 | 参考开源实现（Airflow） |
| 时间不足 | 高 | 高 | 砍掉前端，保留核心功能 |

---

## ✅ 完成本项目后你将掌握

1. **分布式系统设计**
   - 任务调度
   - 资源管理
   - 一致性保障

2. **数据库高级应用**
   - 复杂查询
   - 索引优化
   - 事务处理

3. **后端工程能力**
   - 高并发处理
   - 异步编程
   - 缓存设计

4. **算法与数据结构**
   - 图算法（DAG）
   - 优先级队列
   - 资源分配算法

5. **工程实践**
   - Docker部署
   - 监控告警
   - 压力测试

**这是一个能让你在简历中自信地写上"精通XXX"的项目！** 🚀
