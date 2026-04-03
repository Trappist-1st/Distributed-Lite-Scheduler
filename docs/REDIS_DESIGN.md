# Redis技术应用详细设计 (Redis Design Document)

## 📋 目录
- [1. Redis在系统中的角色](#1-redis在系统中的角色)
- [2. 分布式锁详细设计](#2-分布式锁详细设计)
- [3. 缓存策略设计](#3-缓存策略设计)
- [4. 发布订阅设计](#4-发布订阅设计)
- [5. 数据结构应用](#5-数据结构应用)
- [6. 性能优化实践](#6-性能优化实践)
- [7. 高可用配置](#7-高可用配置)
- [8. 监控与运维](#8-监控与运维)

---

## 1. Redis在系统中的角色

### 1.1 核心职责

```
┌─────────────────────────────────────────────────────────┐
│                     Redis 核心应用                        │
├─────────────────────────────────────────────────────────┤
│  1. 分布式锁 (Distributed Lock)                         │
│     - Scheduler Leader选举                              │
│     - 任务调度并发控制                                   │
│     - 资源分配互斥                                       │
│                                                          │
│  2. 缓存层 (Cache Layer)                                │
│     - 热点数据缓存（用户、项目、配置）                    │
│     - 查询结果缓存                                       │
│     - 分布式会话存储                                     │
│                                                          │
│  3. 消息队列 (Message Queue)                            │
│     - 任务提交削峰队列                                   │
│     - 事件通知（Pub/Sub）                                │
│     - 延迟任务队列                                       │
│                                                          │
│  4. 计数器与统计 (Counter & Stats)                      │
│     - 实时QPS统计                                        │
│     - 限流计数器                                         │
│     - 排行榜（资源使用Top N）                            │
│                                                          │
│  5. 分布式数据结构                                       │
│     - 任务等待队列（List）                               │
│     - 去重集合（Set）                                    │
│     - 优先级队列（Sorted Set）                           │
└─────────────────────────────────────────────────────────┘
```

### 1.2 技术栈选择

| 组件 | 版本 | 用途 |
|------|------|------|
| Redis | 7.0+ | 核心缓存与分布式协调 |
| Redisson | 3.23+ | 分布式锁与数据结构 |
| Spring Data Redis | 3.1+ | Redis集成框架 |
| Lettuce | 6.2+ | 异步Redis客户端 |

---

## 2. 分布式锁详细设计

### 2.1 应用场景总览

#### 场景1：Scheduler Leader选举
**问题**：多个Scheduler实例同时运行，避免重复调度

```java
/**
 * Scheduler Leader选举锁
 * 
 * 关键特性：
 * - 自动续期（Watch Dog）
 * - 实例宕机自动释放
 * - 公平竞争
 */
@Component
@Slf4j
public class SchedulerLeaderElection {
    
    private static final String LEADER_LOCK_KEY = "scheduler:leader:lock";
    private static final long LOCK_LEASE_TIME = 30; // 30秒
    
    @Autowired
    private RedissonClient redisson;
    
    private RLock leaderLock;
    private volatile boolean isLeader = false;
    
    /**
     * 启动Leader选举
     */
    @PostConstruct
    public void startElection() {
        leaderLock = redisson.getLock(LEADER_LOCK_KEY);
        
        // 定时尝试获取Leader身份
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> {
            try {
                tryAcquireLeadership();
            } catch (Exception e) {
                log.error("Leader选举异常", e);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }
    
    /**
     * 尝试获取Leader身份
     */
    private void tryAcquireLeadership() {
        try {
            // 尝试加锁（非阻塞）
            boolean acquired = leaderLock.tryLock(0, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            
            if (acquired && !isLeader) {
                // 成为Leader
                isLeader = true;
                log.info("🎖️ 成为Scheduler Leader，开始调度任务");
                onBecomeLeader();
            } else if (!acquired && isLeader) {
                // 失去Leader身份
                isLeader = false;
                log.warn("❌ 失去Scheduler Leader身份");
                onLoseLeadership();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Leader选举被中断", e);
        }
    }
    
    /**
     * 成为Leader后的操作
     */
    private void onBecomeLeader() {
        // 启动调度器
        taskScheduler.start();
        
        // 启动心跳检测
        healthChecker.start();
        
        // 发送通知
        notificationService.sendAlert("Scheduler Leader已切换");
    }
    
    /**
     * 失去Leader身份后的操作
     */
    private void onLoseLeadership() {
        // 停止调度器
        taskScheduler.stop();
        
        // 停止心跳检测
        healthChecker.stop();
    }
    
    /**
     * 检查是否为Leader
     */
    public boolean isLeader() {
        return isLeader && leaderLock.isHeldByCurrentThread();
    }
    
    /**
     * 优雅关闭
     */
    @PreDestroy
    public void shutdown() {
        if (leaderLock.isHeldByCurrentThread()) {
            leaderLock.unlock();
            log.info("主动释放Leader锁");
        }
    }
}
```

---

#### 场景2：任务调度并发控制
**问题**：多个Scheduler实例可能同时调度同一个任务

```java
/**
 * 任务调度分布式锁
 * 
 * 使用场景：
 * - 防止任务重复调度
 * - 保证任务状态的原子更新
 */
@Service
@Slf4j
public class TaskScheduleService {
    
    @Autowired
    private RedissonClient redisson;
    
    @Autowired
    private TaskInstanceMapper taskMapper;
    
    /**
     * 调度单个任务（带锁）
     */
    public boolean scheduleTask(Long taskId) {
        String lockKey = "task:schedule:lock:" + taskId;
        RLock lock = redisson.getLock(lockKey);
        
        try {
            // 尝试加锁，最多等待3秒，锁10秒后自动释放
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("任务{}正在被其他实例调度，跳过", taskId);
                return false;
            }
            
            // 双重检查：再次查询任务状态
            TaskInstance task = taskMapper.selectById(taskId);
            if (task.getStatus() != TaskStatus.PENDING) {
                log.info("任务{}状态已变更为{}，取消调度", taskId, task.getStatus());
                return false;
            }
            
            // 执行调度逻辑
            return doSchedule(task);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取任务调度锁被中断", e);
            return false;
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * 批量调度任务（使用MultiLock）
     */
    public List<Long> batchScheduleTasks(List<Long> taskIds) {
        // 为每个任务创建锁
        RLock[] locks = taskIds.stream()
            .map(id -> redisson.getLock("task:schedule:lock:" + id))
            .toArray(RLock[]::new);
        
        // 创建MultiLock（红锁的简化版）
        RLock multiLock = redisson.getMultiLock(locks);
        
        try {
            // 尝试同时获取所有锁
            boolean acquired = multiLock.tryLock(5, 15, TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("批量调度失败：无法获取所有任务锁");
                return Collections.emptyList();
            }
            
            // 批量调度
            return doBatchSchedule(taskIds);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }
    
    private boolean doSchedule(TaskInstance task) {
        // 分配资源
        ResourceAllocation allocation = resourceAllocator.allocate(task);
        if (allocation == null) {
            return false;
        }
        
        // 更新任务状态
        task.setStatus(TaskStatus.RUNNING);
        task.setNodeId(allocation.getNodeId());
        task.setStartTime(LocalDateTime.now());
        taskMapper.updateById(task);
        
        // 提交到执行器
        executorService.submit(task);
        
        return true;
    }
    
    private List<Long> doBatchSchedule(List<Long> taskIds) {
        // 批量更新状态
        taskMapper.batchUpdateStatus(taskIds, TaskStatus.RUNNING);
        return taskIds;
    }
}
```

---

#### 场景3：资源分配互斥锁
**问题**：多个任务同时竞争同一资源节点

```java
/**
 * 资源分配分布式锁
 */
@Service
@Slf4j
public class ResourceAllocationService {
    
    @Autowired
    private RedissonClient redisson;
    
    /**
     * 为任务分配资源（带锁）
     */
    public ResourceAllocation allocate(TaskInstance task) {
        // 获取所有可用节点
        List<ResourceNode> availableNodes = resourceNodeMapper.selectAvailable();
        
        for (ResourceNode node : availableNodes) {
            String lockKey = "resource:node:lock:" + node.getId();
            RLock lock = redisson.getLock(lockKey);
            
            try {
                // 尝试锁定节点（非阻塞）
                if (!lock.tryLock(0, 5, TimeUnit.SECONDS)) {
                    continue;
                }
                
                // 再次检查资源是否充足（可能被其他线程占用）
                node = resourceNodeMapper.selectById(node.getId());
                if (!node.canFit(task.getResourceRequirement())) {
                    continue;
                }
                
                // 分配资源
                ResourceAllocation allocation = doAllocate(task, node);
                
                log.info("任务{}成功分配到节点{}", task.getId(), node.getId());
                return allocation;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        
        log.warn("任务{}无法分配资源", task.getId());
        return null;
    }
    
    private ResourceAllocation doAllocate(TaskInstance task, ResourceNode node) {
        // 更新节点资源
        node.setAvailableCpu(node.getAvailableCpu() - task.getCpuRequired());
        node.setAvailableMemory(node.getAvailableMemory() - task.getMemoryRequired());
        resourceNodeMapper.updateById(node);
        
        // 记录分配
        ResourceAllocation allocation = ResourceAllocation.builder()
            .taskId(task.getId())
            .nodeId(node.getId())
            .cpuAllocated(task.getCpuRequired())
            .memoryAllocated(task.getMemoryRequired())
            .build();
        
        resourceAllocationMapper.insert(allocation);
        return allocation;
    }
}
```

---

### 2.2 Redisson配置详解

```java
/**
 * Redisson配置类
 */
@Configuration
public class RedissonConfig {
    
    @Value("${spring.redis.host}")
    private String host;
    
    @Value("${spring.redis.port}")
    private int port;
    
    @Value("${spring.redis.password}")
    private String password;
    
    @Value("${spring.redis.database:0}")
    private int database;
    
    /**
     * 单机模式配置
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson() {
        Config config = new Config();
        
        // 单机配置
        SingleServerConfig serverConfig = config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setPassword(password)
            .setDatabase(database)
            .setConnectionMinimumIdleSize(10)      // 最小空闲连接
            .setConnectionPoolSize(64)             // 连接池大小
            .setIdleConnectionTimeout(10000)       // 空闲连接超时
            .setConnectTimeout(10000)              // 连接超时
            .setTimeout(3000)                      // 命令超时
            .setRetryAttempts(3)                   // 重试次数
            .setRetryInterval(1500);               // 重试间隔
        
        // Watch Dog配置（自动续期）
        config.setLockWatchdogTimeout(30000L);     // 看门狗超时时间30秒
        
        return Redisson.create(config);
    }
    
    /**
     * 集群模式配置（生产环境推荐）
     */
    @Bean(destroyMethod = "shutdown")
    @Profile("prod")
    public RedissonClient redissonCluster() {
        Config config = new Config();
        
        config.useClusterServers()
            .addNodeAddress(
                "redis://192.168.1.100:6379",
                "redis://192.168.1.101:6379",
                "redis://192.168.1.102:6379"
            )
            .setPassword(password)
            .setMasterConnectionMinimumIdleSize(10)
            .setMasterConnectionPoolSize(64)
            .setSlaveConnectionMinimumIdleSize(10)
            .setSlaveConnectionPoolSize(64)
            .setReadMode(ReadMode.SLAVE)           // 读从节点
            .setSubscriptionMode(SubscriptionMode.MASTER); // 订阅主节点
        
        return Redisson.create(config);
    }
}
```

---

### 2.3 锁的高级特性

#### 可重入锁（Reentrant Lock）
```java
/**
 * 可重入锁示例
 */
public void recursiveMethod(Long taskId, int level) {
    RLock lock = redisson.getLock("task:lock:" + taskId);
    
    try {
        lock.lock();
        
        // 业务逻辑
        if (level > 0) {
            recursiveMethod(taskId, level - 1); // 可重入
        }
        
    } finally {
        lock.unlock();
    }
}
```

#### 公平锁（Fair Lock）
```java
/**
 * 公平锁：按照请求顺序获取锁
 */
RLock fairLock = redisson.getFairLock("fair:lock:key");
fairLock.lock();
try {
    // 按顺序执行
} finally {
    fairLock.unlock();
}
```

#### 读写锁（Read-Write Lock）
```java
/**
 * 读写锁：读多写少场景
 */
@Service
public class ConfigService {
    
    @Autowired
    private RedissonClient redisson;
    
    private RReadWriteLock rwLock = redisson.getReadWriteLock("config:lock");
    
    /**
     * 读配置（允许多个线程同时读）
     */
    public Config getConfig() {
        RLock readLock = rwLock.readLock();
        readLock.lock();
        try {
            return configMapper.selectConfig();
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * 写配置（独占锁）
     */
    public void updateConfig(Config config) {
        RLock writeLock = rwLock.writeLock();
        writeLock.lock();
        try {
            configMapper.updateConfig(config);
            // 清除缓存
            cacheManager.evict("config");
        } finally {
            writeLock.unlock();
        }
    }
}
```

#### 信号量（Semaphore）
```java
/**
 * 限制并发执行数量
 */
@Service
public class ConcurrentTaskExecutor {
    
    @Autowired
    private RedissonClient redisson;
    
    /**
     * 限制最多10个任务同时执行
     */
    public void execute(Task task) {
        RSemaphore semaphore = redisson.getSemaphore("task:concurrent:limit");
        semaphore.trySetPermits(10); // 设置许可数
        
        try {
            // 获取许可（阻塞）
            semaphore.acquire();
            
            // 执行任务
            doExecute(task);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 释放许可
            semaphore.release();
        }
    }
    
    private void doExecute(Task task) {
        // 任务执行逻辑
    }
}
```

#### 倒计数器（CountDownLatch）
```java
/**
 * 等待多个子任务完成
 */
public void executeWorkflow(Workflow workflow) {
    List<Task> parallelTasks = workflow.getParallelTasks();
    
    // 创建倒计数器
    RCountDownLatch latch = redisson.getCountDownLatch("workflow:" + workflow.getId());
    latch.trySetCount(parallelTasks.size());
    
    // 提交所有任务
    for (Task task : parallelTasks) {
        executorService.submit(() -> {
            try {
                executeTask(task);
            } finally {
                latch.countDown(); // 完成一个
            }
        });
    }
    
    // 等待所有任务完成
    try {
        latch.await(1, TimeUnit.HOURS); // 最多等1小时
        log.info("工作流{}的所有并行任务已完成", workflow.getId());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 3. 缓存策略设计

### 3.1 多层缓存架构

```
┌─────────────────────────────────────────────────────┐
│                  应用层请求                          │
└────────────────────┬────────────────────────────────┘
                     │
         ┌───────────▼──────────┐
         │  L1: 本地缓存 (Caffeine)│
         │  - 用户会话 (5min)     │
         │  - 配置信息 (30min)    │
         └───────────┬───────────┘
                     │ Cache Miss
         ┌───────────▼──────────┐
         │  L2: Redis缓存        │
         │  - 用户信息 (30min)   │
         │  - 项目信息 (10min)   │
         │  - 查询结果 (5min)    │
         └───────────┬───────────┘
                     │ Cache Miss
         ┌───────────▼──────────┐
         │  L3: 数据库           │
         │  - MySQL/PostgreSQL  │
         └──────────────────────┘
```

### 3.2 缓存实现代码

#### 通用缓存工具类
```java
/**
 * Redis缓存工具类
 */
@Component
@Slf4j
public class RedisCacheService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 设置缓存（带TTL）
     */
    public <T> void set(String key, T value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("Redis缓存设置失败: key={}", key, e);
        }
    }
    
    /**
     * 获取缓存
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        try {
            return (T) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis缓存获取失败: key={}", key, e);
            return null;
        }
    }
    
    /**
     * 删除缓存
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis缓存删除失败: key={}", key, e);
        }
    }
    
    /**
     * 批量删除（通配符）
     */
    public void deletePattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("批量删除缓存: pattern={}, count={}", pattern, keys.size());
            }
        } catch (Exception e) {
            log.error("Redis批量删除失败: pattern={}", pattern, e);
        }
    }
    
    /**
     * 检查key是否存在
     */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Redis存在性检查失败: key={}", key, e);
            return false;
        }
    }
    
    /**
     * 设置过期时间
     */
    public boolean expire(String key, long timeout, TimeUnit unit) {
        try {
            return Boolean.TRUE.equals(redisTemplate.expire(key, timeout, unit));
        } catch (Exception e) {
            log.error("Redis设置过期时间失败: key={}", key, e);
            return false;
        }
    }
}
```

#### Cache-Aside模式实现
```java
/**
 * 用户服务缓存实现
 */
@Service
@Slf4j
public class UserService {
    
    private static final String USER_CACHE_PREFIX = "user:info:";
    private static final long USER_CACHE_TTL = 30; // 30分钟
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private RedisCacheService cacheService;
    
    /**
     * 获取用户信息（带缓存）
     */
    public User getUserById(Long userId) {
        String cacheKey = USER_CACHE_PREFIX + userId;
        
        // 1. 尝试从缓存获取
        User user = cacheService.get(cacheKey, User.class);
        if (user != null) {
            log.debug("从缓存获取用户: userId={}", userId);
            return user;
        }
        
        // 2. 缓存未命中，查询数据库
        user = userMapper.selectById(userId);
        if (user == null) {
            // 防止缓存穿透：缓存空对象
            cacheService.set(cacheKey, new User(), 5, TimeUnit.MINUTES);
            return null;
        }
        
        // 3. 写入缓存
        cacheService.set(cacheKey, user, USER_CACHE_TTL, TimeUnit.MINUTES);
        log.debug("从数据库加载用户并缓存: userId={}", userId);
        
        return user;
    }
    
    /**
     * 更新用户信息（删除缓存）
     */
    @Transactional
    public void updateUser(User user) {
        // 1. 更新数据库
        userMapper.updateById(user);
        
        // 2. 删除缓存
        String cacheKey = USER_CACHE_PREFIX + user.getId();
        cacheService.delete(cacheKey);
        
        log.info("更新用户并删除缓存: userId={}", user.getId());
    }
    
    /**
     * 删除用户（删除缓存）
     */
    @Transactional
    public void deleteUser(Long userId) {
        // 1. 删除数据库
        userMapper.deleteById(userId);
        
        // 2. 删除缓存
        String cacheKey = USER_CACHE_PREFIX + userId;
        cacheService.delete(cacheKey);
        
        log.info("删除用户并删除缓存: userId={}", userId);
    }
}
```

---

### 3.3 缓存高级策略

#### 缓存预热
```java
/**
 * 缓存预热：系统启动时加载热点数据
 */
@Component
@Slf4j
public class CacheWarmer {
    
    @Autowired
    private ProjectMapper projectMapper;
    
    @Autowired
    private RedisCacheService cacheService;
    
    /**
     * 应用启动后预热缓存
     */
    @PostConstruct
    public void warmUp() {
        log.info("开始缓存预热...");
        
        // 加载所有活跃项目
        List<Project> activeProjects = projectMapper.selectActiveProjects();
        for (Project project : activeProjects) {
            String cacheKey = "project:info:" + project.getId();
            cacheService.set(cacheKey, project, 30, TimeUnit.MINUTES);
        }
        
        log.info("缓存预热完成: 加载了{}个活跃项目", activeProjects.size());
    }
}
```

#### 缓存穿透防护
```java
/**
 * 防止缓存穿透：布隆过滤器
 */
@Service
public class TaskService {
    
    @Autowired
    private RedissonClient redisson;
    
    private RBloomFilter<Long> taskIdFilter;
    
    @PostConstruct
    public void init() {
        // 创建布隆过滤器
        taskIdFilter = redisson.getBloomFilter("task:id:filter");
        // 预期10万个元素，误判率0.01
        taskIdFilter.tryInit(100000L, 0.01);
        
        // 加载所有任务ID到布隆过滤器
        List<Long> taskIds = taskMapper.selectAllIds();
        taskIds.forEach(taskIdFilter::add);
    }
    
    public Task getTaskById(Long taskId) {
        // 1. 先检查布隆过滤器
        if (!taskIdFilter.contains(taskId)) {
            log.warn("任务不存在（布隆过滤器）: taskId={}", taskId);
            return null;
        }
        
        // 2. 查询缓存
        String cacheKey = "task:info:" + taskId;
        Task task = cacheService.get(cacheKey, Task.class);
        if (task != null) {
            return task;
        }
        
        // 3. 查询数据库
        task = taskMapper.selectById(taskId);
        if (task != null) {
            cacheService.set(cacheKey, task, 10, TimeUnit.MINUTES);
        }
        
        return task;
    }
}
```

#### 缓存雪崩防护
```java
/**
 * 防止缓存雪崩：随机TTL
 */
public void setCacheWithRandomTTL(String key, Object value, long baseTTL, TimeUnit unit) {
    // 在基础TTL上增加随机时间（±20%）
    Random random = new Random();
    long randomOffset = (long) (baseTTL * 0.2 * (random.nextDouble() - 0.5) * 2);
    long finalTTL = baseTTL + randomOffset;
    
    cacheService.set(key, value, finalTTL, unit);
    log.debug("设置缓存: key={}, ttl={}{}", key, finalTTL, unit);
}
```

#### 缓存击穿防护（热点数据）
```java
/**
 * 防止缓存击穿：分布式锁+双重检查
 */
@Service
public class HotDataService {
    
    @Autowired
    private RedissonClient redisson;
    
    public Data getHotData(String key) {
        String cacheKey = "hot:data:" + key;
        
        // 1. 尝试从缓存获取
        Data data = cacheService.get(cacheKey, Data.class);
        if (data != null) {
            return data;
        }
        
        // 2. 缓存未命中，加锁
        String lockKey = "hot:data:lock:" + key;
        RLock lock = redisson.getLock(lockKey);
        
        try {
            // 获取锁
            lock.lock();
            
            // 3. 双重检查：再次查询缓存
            data = cacheService.get(cacheKey, Data.class);
            if (data != null) {
                return data;
            }
            
            // 4. 查询数据库
            data = dataMapper.selectByKey(key);
            
            // 5. 写入缓存
            if (data != null) {
                cacheService.set(cacheKey, data, 60, TimeUnit.MINUTES);
            }
            
            return data;
            
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 4. 发布订阅设计

### 4.1 事件通知系统

```java
/**
 * Redis Pub/Sub事件总线
 */
@Component
@Slf4j
public class RedisEventBus {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 发布事件
     */
    public void publish(String channel, Object message) {
        try {
            redisTemplate.convertAndSend(channel, message);
            log.debug("发布事件: channel={}, message={}", channel, message);
        } catch (Exception e) {
            log.error("发布事件失败", e);
        }
    }
    
    /**
     * 订阅事件
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // 注册监听器
        container.addMessageListener(taskStatusListener(), 
            new PatternTopic("task:status:*"));
        
        container.addMessageListener(resourceChangeListener(), 
            new PatternTopic("resource:change:*"));
        
        return container;
    }
    
    /**
     * 任务状态变更监听器
     */
    @Bean
    public MessageListenerAdapter taskStatusListener() {
        return new MessageListenerAdapter(new Object() {
            @SuppressWarnings("unused")
            public void handleMessage(String message) {
                log.info("收到任务状态变更: {}", message);
                // 处理任务状态变更
                TaskStatusEvent event = JSON.parseObject(message, TaskStatusEvent.class);
                handleTaskStatusChange(event);
            }
        }, "handleMessage");
    }
    
    /**
     * 资源变更监听器
     */
    @Bean
    public MessageListenerAdapter resourceChangeListener() {
        return new MessageListenerAdapter(new Object() {
            @SuppressWarnings("unused")
            public void handleMessage(String message) {
                log.info("收到资源变更: {}", message);
                // 处理资源变更
                ResourceChangeEvent event = JSON.parseObject(message, ResourceChangeEvent.class);
                handleResourceChange(event);
            }
        }, "handleMessage");
    }
}
```

### 4.2 实时通知推送

```java
/**
 * 任务状态变更通知
 */
@Service
@Slf4j
public class TaskNotificationService {
    
    private static final String TASK_STATUS_CHANNEL = "task:status:";
    
    @Autowired
    private RedisEventBus eventBus;
    
    /**
     * 通知任务状态变更
     */
    public void notifyTaskStatusChange(Long taskId, TaskStatus oldStatus, TaskStatus newStatus) {
        TaskStatusEvent event = TaskStatusEvent.builder()
            .taskId(taskId)
            .oldStatus(oldStatus)
            .newStatus(newStatus)
            .timestamp(System.currentTimeMillis())
            .build();
        
        String channel = TASK_STATUS_CHANNEL + taskId;
        eventBus.publish(channel, JSON.toJSONString(event));
        
        log.info("任务状态变更通知: taskId={}, {} -> {}", taskId, oldStatus, newStatus);
    }
}
```

---

## 5. 数据结构应用

### 5.1 任务队列（List）

```java
/**
 * 任务提交队列（削峰）
 */
@Service
@Slf4j
public class TaskSubmitQueue {
    
    private static final String QUEUE_KEY = "task:submit:queue";
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 提交任务到队列
     */
    public void enqueue(Task task) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, task);
        log.debug("任务入队: taskId={}", task.getId());
    }
    
    /**
     * 从队列获取任务（阻塞）
     */
    public Task dequeue(long timeout, TimeUnit unit) {
        Object result = redisTemplate.opsForList()
            .leftPop(QUEUE_KEY, timeout, unit);
        
        if (result != null) {
            Task task = (Task) result;
            log.debug("任务出队: taskId={}", task.getId());
            return task;
        }
        
        return null;
    }
    
    /**
     * 批量出队
     */
    public List<Task> dequeueBatch(int batchSize) {
        List<Task> tasks = new ArrayList<>();
        
        for (int i = 0; i < batchSize; i++) {
            Object result = redisTemplate.opsForList().leftPop(QUEUE_KEY);
            if (result == null) {
                break;
            }
            tasks.add((Task) result);
        }
        
        log.debug("批量出队: count={}", tasks.size());
        return tasks;
    }
    
    /**
     * 获取队列长度
     */
    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
}
```

### 5.2 任务去重（Set）

```java
/**
 * 任务去重集合
 */
@Service
public class TaskDeduplicator {
    
    private static final String DEDUPE_KEY_PREFIX = "task:dedupe:";
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 检查任务是否重复
     */
    public boolean isDuplicate(String taskKey) {
        String dedupeKey = DEDUPE_KEY_PREFIX + LocalDate.now();
        
        // 尝试添加到Set
        Long result = redisTemplate.opsForSet().add(dedupeKey, taskKey);
        
        if (result != null && result > 0) {
            // 添加成功，不重复
            // 设置过期时间（1天）
            redisTemplate.expire(dedupeKey, 1, TimeUnit.DAYS);
            return false;
        }
        
        // 添加失败，重复
        return true;
    }
    
    /**
     * 移除去重标记
     */
    public void removeDedupe(String taskKey) {
        String dedupeKey = DEDUPE_KEY_PREFIX + LocalDate.now();
        redisTemplate.opsForSet().remove(dedupeKey, taskKey);
    }
}
```

### 5.3 优先级队列（Sorted Set）

```java
/**
 * 优先级任务队列
 */
@Service
@Slf4j
public class PriorityTaskQueue {
    
    private static final String PRIORITY_QUEUE_KEY = "task:priority:queue";
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 添加任务到优先级队列
     */
    public void add(Task task) {
        // 计算优先级分数：优先级 * 1000 + 时间权重
        double score = calculateScore(task);
        
        redisTemplate.opsForZSet().add(PRIORITY_QUEUE_KEY, task, score);
        log.debug("任务加入优先级队列: taskId={}, score={}", task.getId(), score);
    }
    
    /**
     * 获取最高优先级任务
     */
    public Task poll() {
        Set<Object> result = redisTemplate.opsForZSet()
            .range(PRIORITY_QUEUE_KEY, 0, 0);
        
        if (result != null && !result.isEmpty()) {
            Task task = (Task) result.iterator().next();
            // 移除任务
            redisTemplate.opsForZSet().remove(PRIORITY_QUEUE_KEY, task);
            log.debug("取出最高优先级任务: taskId={}", task.getId());
            return task;
        }
        
        return null;
    }
    
    /**
     * 批量获取高优先级任务
     */
    public List<Task> pollBatch(int count) {
        Set<Object> result = redisTemplate.opsForZSet()
            .range(PRIORITY_QUEUE_KEY, 0, count - 1);
        
        if (result != null && !result.isEmpty()) {
            List<Task> tasks = result.stream()
                .map(obj -> (Task) obj)
                .collect(Collectors.toList());
            
            // 批量移除
            redisTemplate.opsForZSet().remove(PRIORITY_QUEUE_KEY, result.toArray());
            
            log.debug("批量取出高优先级任务: count={}", tasks.size());
            return tasks;
        }
        
        return Collections.emptyList();
    }
    
    /**
     * 计算任务分数
     */
    private double calculateScore(Task task) {
        // 优先级：HIGH=3, MEDIUM=2, LOW=1
        int priorityWeight = task.getPriority().getWeight();
        
        // 等待时间（分钟）
        long waitMinutes = Duration.between(task.getCreateTime(), LocalDateTime.now())
            .toMinutes();
        
        // 分数 = 优先级 * 10000 - 等待时间
        // （分数越小越优先，等待越久越优先）
        return priorityWeight * 10000.0 - waitMinutes;
    }
    
    /**
     * 获取队列长度
     */
    public long size() {
        Long size = redisTemplate.opsForZSet().size(PRIORITY_QUEUE_KEY);
        return size != null ? size : 0;
    }
}
```

### 5.4 延迟队列（Sorted Set + 定时任务）

```java
/**
 * 延迟任务队列
 */
@Service
@Slf4j
public class DelayedTaskQueue {
    
    private static final String DELAYED_QUEUE_KEY = "task:delayed:queue";
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private TaskScheduleService scheduleService;
    
    /**
     * 添加延迟任务
     */
    public void addDelayed(Task task, long delaySeconds) {
        // 使用执行时间戳作为分数
        long executeTime = System.currentTimeMillis() + delaySeconds * 1000;
        
        redisTemplate.opsForZSet().add(DELAYED_QUEUE_KEY, task, executeTime);
        log.info("添加延迟任务: taskId={}, delaySeconds={}", task.getId(), delaySeconds);
    }
    
    /**
     * 定时扫描到期任务
     */
    @Scheduled(fixedDelay = 1000) // 每秒扫描一次
    public void processDelayedTasks() {
        long now = System.currentTimeMillis();
        
        // 获取所有到期的任务
        Set<Object> expiredTasks = redisTemplate.opsForZSet()
            .rangeByScore(DELAYED_QUEUE_KEY, 0, now);
        
        if (expiredTasks != null && !expiredTasks.isEmpty()) {
            log.info("发现{}个到期任务", expiredTasks.size());
            
            for (Object obj : expiredTasks) {
                Task task = (Task) obj;
                try {
                    // 调度任务
                    scheduleService.scheduleTask(task.getId());
                    
                    // 从延迟队列移除
                    redisTemplate.opsForZSet().remove(DELAYED_QUEUE_KEY, task);
                    
                } catch (Exception e) {
                    log.error("处理延迟任务失败: taskId={}", task.getId(), e);
                }
            }
        }
    }
}
```

---

## 6. 性能优化实践

### 6.1 Pipeline批量操作

```java
/**
 * 使用Pipeline批量操作
 */
@Service
public class RedisPipelineService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 批量设置缓存
     */
    public void batchSet(Map<String, Object> data) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                data.forEach((key, value) -> {
                    operations.opsForValue().set(key, value, 30, TimeUnit.MINUTES);
                });
                return null;
            }
        });
        
        log.info("批量设置缓存: count={}", data.size());
    }
    
    /**
     * 批量获取缓存
     */
    public List<Object> batchGet(List<String> keys) {
        return redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                keys.forEach(operations.opsForValue()::get);
                return null;
            }
        });
    }
}
```

### 6.2 序列化优化

```java
/**
 * Redis序列化配置
 */
@Configuration
public class RedisSerializationConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用Jackson序列化（性能优于JDK序列化）
        Jackson2JsonRedisSerializer<Object> serializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );
        serializer.setObjectMapper(mapper);
        
        // Key使用String序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Value使用Jackson序列化
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
```

### 6.3 连接池优化

```yaml
# application.yml
spring:
  redis:
    host: localhost
    port: 6379
    password: ${REDIS_PASSWORD}
    database: 0
    timeout: 3000ms
    
    # Lettuce连接池配置
    lettuce:
      pool:
        max-active: 200      # 最大连接数
        max-idle: 50         # 最大空闲连接
        min-idle: 10         # 最小空闲连接
        max-wait: 3000ms     # 最大等待时间
      shutdown-timeout: 100ms
```

---

## 7. 高可用配置

### 7.1 Redis哨兵模式

```java
/**
 * Redis哨兵配置
 */
@Configuration
@Profile("prod")
public class RedisSentinelConfig {
    
    @Bean
    public RedissonClient redissonSentinel() {
        Config config = new Config();
        
        config.useSentinelServers()
            .setMasterName("scheduler-master")
            .addSentinelAddress(
                "redis://sentinel1:26379",
                "redis://sentinel2:26379",
                "redis://sentinel3:26379"
            )
            .setPassword("password")
            .setDatabase(0)
            .setMasterConnectionPoolSize(64)
            .setSlaveConnectionPoolSize(64)
            .setReadMode(ReadMode.SLAVE)  // 读从节点
            .setSubscriptionMode(SubscriptionMode.MASTER)
            .setFailedSlaveCheckInterval(10000)  // 从节点健康检查间隔
            .setFailedSlaveReconnectionInterval(5000);  // 从节点重连间隔
        
        return Redisson.create(config);
    }
}
```

### 7.2 Redis集群模式

```yaml
# application-prod.yml
spring:
  redis:
    cluster:
      nodes:
        - 192.168.1.101:6379
        - 192.168.1.102:6379
        - 192.168.1.103:6379
        - 192.168.1.104:6379
        - 192.168.1.105:6379
        - 192.168.1.106:6379
      max-redirects: 3
    password: ${REDIS_PASSWORD}
    lettuce:
      cluster:
        refresh:
          adaptive: true          # 自适应拓扑刷新
          period: 30s             # 定期刷新间隔
```

---

## 8. 监控与运维

### 8.1 Redis监控指标

```java
/**
 * Redis监控
 */
@Component
@Slf4j
public class RedisMonitor {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    /**
     * 定时采集Redis指标
     */
    @Scheduled(fixedRate = 60000) // 每分钟
    public void collectMetrics() {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) connection -> 
                connection.info()
            );
            
            if (info != null) {
                // 内存使用
                long usedMemory = Long.parseLong(info.getProperty("used_memory", "0"));
                meterRegistry.gauge("redis.memory.used", usedMemory);
                
                // 连接数
                int connectedClients = Integer.parseInt(info.getProperty("connected_clients", "0"));
                meterRegistry.gauge("redis.clients.connected", connectedClients);
                
                // 命令统计
                long totalCommands = Long.parseLong(info.getProperty("total_commands_processed", "0"));
                meterRegistry.gauge("redis.commands.total", totalCommands);
                
                // 命中率
                long keyspaceHits = Long.parseLong(info.getProperty("keyspace_hits", "0"));
                long keyspaceMisses = Long.parseLong(info.getProperty("keyspace_misses", "0"));
                double hitRate = (double) keyspaceHits / (keyspaceHits + keyspaceMisses + 1);
                meterRegistry.gauge("redis.hitrate", hitRate);
                
                log.debug("Redis指标采集完成: memory={}MB, clients={}, hitRate={}%", 
                    usedMemory / 1024 / 1024, connectedClients, hitRate * 100);
            }
            
        } catch (Exception e) {
            log.error("Redis指标采集失败", e);
        }
    }
}
```

### 8.2 慢查询日志

```java
/**
 * Redis慢查询监控
 */
@Component
@Slf4j
public class RedisSlowLogMonitor {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 定时检查慢查询
     */
    @Scheduled(fixedRate = 300000) // 每5分钟
    public void checkSlowLog() {
        try {
            List<Object> slowLogs = redisTemplate.execute((RedisCallback<List<Object>>) connection -> 
                connection.slowLogGet(10) // 获取最近10条慢查询
            );
            
            if (slowLogs != null && !slowLogs.isEmpty()) {
                log.warn("发现{}条Redis慢查询", slowLogs.size());
                
                for (Object log : slowLogs) {
                    // 打印慢查询详情
                    log.warn("慢查询: {}", log);
                }
            }
            
        } catch (Exception e) {
            log.error("慢查询检查失败", e);
        }
    }
}
```

### 8.3 健康检查

```java
/**
 * Redis健康检查
 */
@Component
public class RedisHealthCheck implements HealthIndicator {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Override
    public Health health() {
        try {
            // 执行PING命令
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> 
                connection.ping()
            );
            
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up()
                    .withDetail("redis", "available")
                    .build();
            } else {
                return Health.down()
                    .withDetail("redis", "ping failed")
                    .build();
            }
            
        } catch (Exception e) {
            return Health.down()
                .withDetail("redis", "unavailable")
                .withException(e)
                .build();
        }
    }
}
```

---

## 9. 最佳实践总结

### 9.1 Key命名规范

```
业务模块:数据类型:具体标识[:子标识]

示例：
- user:info:12345                 # 用户信息
- task:instance:67890             # 任务实例
- project:config:abc              # 项目配置
- resource:node:lock:10           # 资源节点锁
- workflow:dag:cache:workflow_5   # 工作流DAG缓存
```

### 9.2 过期时间设置

| 数据类型 | TTL | 说明 |
|---------|-----|------|
| 用户会话 | 30分钟 | 活跃用户 |
| 项目配置 | 1小时 | 变更不频繁 |
| 任务信息 | 10分钟 | 实时性要求高 |
| 统计数据 | 5分钟 | 频繁变化 |
| 分布式锁 | 30秒 | 自动续期 |
| 去重标记 | 24小时 | 防止重复提交 |

### 9.3 性能优化checklist

- [ ] 使用Pipeline批量操作
- [ ] 使用合适的数据结构
- [ ] 避免大Key（单个Key > 10KB）
- [ ] 避免热Key（QPS > 1000）
- [ ] 设置合理的过期时间
- [ ] 使用连接池
- [ ] 开启持久化（AOF + RDB）
- [ ] 监控内存使用
- [ ] 定期清理无用Key

### 9.4 注意事项

⚠️ **避免的操作**
- ❌ KEYS * （生产环境禁止）
- ❌ FLUSHDB / FLUSHALL
- ❌ 大Value存储（> 1MB）
- ❌ 不设置过期时间（内存泄漏）
- ❌ 同步阻塞操作

✅ **推荐的操作**
- ✅ 使用SCAN代替KEYS
- ✅ 使用Pipeline批量操作
- ✅ 设置合理的TTL
- ✅ 监控慢查询
- ✅ 使用异步客户端（Lettuce）

---

## 10. 面试要点

### 10.1 Redis在项目中的作用
> "在我的分布式任务调度系统中，Redis扮演了三个核心角色：
> 1. **分布式锁**：实现Scheduler Leader选举和任务调度的并发控制
> 2. **缓存层**：缓存用户、项目、配置等热点数据，减轻数据库压力
> 3. **消息队列**：实现任务提交削峰和实时事件通知"

### 10.2 如何保证分布式锁的可靠性
> "使用Redisson的RedLock实现，具备以下特性：
> 1. **Watch Dog自动续期**：防止业务超时导致锁提前释放
> 2. **可重入**：同一线程可以多次获取同一把锁
> 3. **超时自动释放**：防止死锁
> 4. **Lua脚本保证原子性**：解锁时验证持有者"

### 10.3 如何防止缓存穿透/雪崩/击穿
> "采用了多层防护：
> 1. **缓存穿透**：布隆过滤器 + 空值缓存
> 2. **缓存雪崩**：随机TTL + 多级缓存
> 3. **缓存击穿**：分布式锁 + 双重检查"

### 10.4 Redis性能优化措施
> "1. 使用Pipeline批量操作，减少网络往返
> 2. 选择合适的数据结构（Sorted Set实现优先级队列）
> 3. 连接池优化（Lettuce连接池配置）
> 4. 读写分离（主写从读）
> 5. 定期监控慢查询和内存使用"

---

**本文档详细设计了Redis在分布式任务调度系统中的各种应用场景，包含完整的代码实现和最佳实践，是简历和面试的重要技术亮点！** 🚀
