# 分布式任务调度与资源管理系统 (Distributed Lite Scheduler)

## 🎯 产品定位（Product Positioning）

### 核心价值主张
**面向中小团队的生产级轻量分布式调度平台**，对标：XXL-Job（简化版）+ Kubernetes Job Scheduler（概念级）+ Apache Airflow（轻量版）

### 目标用户场景
1. **数据团队**：ETL任务编排、模型训练调度
2. **开发团队**：定时任务管理、批处理任务
3. **小型AI团队**：GPU资源调度、训练任务排队

### 产品差异化（Why Not Just Use XXL-Job?）
- ✅ **资源感知调度**（CPU/GPU/内存限制）
- ✅ **DAG工作流支持**（任务依赖编排）
- ✅ **多租户资源隔离**
- ✅ **可视化监控面板**
- ✅ **插件化执行器**（支持Shell/Python/Docker）

---

## 🏗️ 系统架构设计（System Architecture）

```
┌─────────────────────────────────────────────────────────────┐
│                      Web UI / CLI / OpenAPI                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                   API Gateway (Spring Cloud Gateway)         │
│              [限流] [认证] [熔断] [日志追踪]                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
│ Scheduler    │ │ Executor │ │ Monitor     │
│ 调度核心模块    │ │ 执行器集群 │ │ 监控告警模块  │
└───────┬──────┘ └────┬─────┘ └──────┬──────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
│ MySQL/PG     │ │ Redis    │ │ MinIO/OSS   │
│ 元数据存储     │ │ 分布式锁  │ │ 日志存储     │
└──────────────┘ └──────────┘ └─────────────┘
```

---

## 🧠 核心技术亮点（Technical Highlights）

### 1. 高级调度算法
- **Fair Scheduling**：多租户公平调度
- **Priority Queue**：优先级队列（堆实现）
- **Backfill Scheduling**：资源填充算法（借鉴HPC调度）
- **Preemption**：任务抢占机制（可选）

### 2. DAG工作流引擎
```
Task A → Task B ↘
              Task D → Task E
Task C ────────↗
```
- 依赖解析（拓扑排序）
- 并行执行（分支并发）
- 失败重试（指数退避）

### 3. 分布式一致性保障
- **Redis分布式锁**（Redisson）
  - **Scheduler Leader选举**：多实例环境下通过Redis锁选举主调度器，避免重复调度
  - **任务调度互斥锁**：防止同一任务被多个Scheduler实例同时调度
  - **资源分配锁**：保证多个任务竞争同一资源节点时的互斥访问
  - **Watch Dog自动续期**：Redisson自动续期机制，防止业务执行时间超过锁超时时间
- **数据库乐观锁**（version字段）
  - 任务状态更新时的并发控制
  - 资源节点可用量更新的并发控制
- **幂等性设计**（任务ID去重）
  - 基于Redis Set的任务去重（24小时窗口）
  - 工作流实例ID全局唯一性保证

### 4. 资源智能调度
- **Bin Packing算法**：资源装箱优化
- **预留资源**：避免资源碎片化
- **动态扩缩容**：Worker节点自动注册/注销

### 5. 高可用设计
- **Scheduler多实例**（Leader选举）
- **任务心跳检测**（超时自动重调度）
- **执行器健康检查**（定期Ping）

---

## 📊 数据库设计增强（Enhanced Schema）

### 新增实体
| 实体 | 说明 | 核心字段 |
|------|------|----------|
| `Workflow` | 工作流定义 | dag_json, cron_expr |
| `WorkflowInstance` | 工作流实例 | status, trigger_type |
| `TaskDependency` | 任务依赖关系 | parent_task_id, child_task_id |
| `ResourceQuota` | 租户资源配额 | max_cpu, max_memory |
| `ScheduleStrategy` | 调度策略 | algorithm_type, priority_weight |
| `AlertRule` | 告警规则 | condition, notification_channel |
| `ExecutionLog` | 执行日志（大表） | stdout, stderr, exit_code |
| `MetricSnapshot` | 性能快照 | cpu_usage, mem_usage, timestamp |

### 关键索引设计（Performance Critical）
```sql
-- 高频查询优化
CREATE INDEX idx_task_status_priority ON task_instance(status, priority, create_time);
CREATE INDEX idx_workflow_schedule ON workflow(status, next_schedule_time);
CREATE INDEX idx_resource_available ON resource_node(available_cpu, available_gpu);

-- 分区表（大表优化）
ALTER TABLE execution_log PARTITION BY RANGE (create_time) (
    PARTITION p2024 VALUES LESS THAN ('2025-01-01'),
    PARTITION p2025 VALUES LESS THAN ('2026-01-01')
);
```

---

## 🚀 高级功能模块（Advanced Features）

### Module 1: DAG工作流引擎
**实现难点**：
- 循环依赖检测
- 动态依赖（运行时生成子任务）
- 条件分支（If/Else）

**设计方案**：
```java
// 拓扑排序 + 并行执行
class DAGScheduler {
    Map<Long, Set<Long>> buildDependencyGraph();
    List<List<Task>> topologicalSort();
    void executeLevel(List<Task> parallelTasks);
}
```

### Module 2: 多租户资源隔离
**实现难点**：
- 配额管理（硬限制 vs 软限制）
- 资源抢占（低优先级让路）
- 公平性保证（防饥饿）

**设计方案**：
```java
// 租户配额管理器
class TenantQuotaManager {
    boolean checkQuota(Long tenantId, ResourceRequest req);
    void reserveResource(Long tenantId, ResourceAllocation alloc);
    void releaseResource(Long tenantId, ResourceAllocation alloc);
}
```

### Module 3: 动态资源调度器
**算法选择**：
- **First Fit**：快速分配（O(n)）
- **Best Fit**：最优匹配（减少碎片）
- **DRF (Dominant Resource Fairness)**：多资源公平

**伪代码**：
```python
def schedule_task(task, nodes):
    # Best Fit算法
    best_node = None
    min_waste = float('inf')
    
    for node in nodes:
        if node.can_fit(task):
            waste = node.available - task.required
            if waste < min_waste:
                min_waste = waste
                best_node = node
    
    return best_node
```

### Module 4: 任务重试与容错
**策略设计**：
- **指数退避**：1s, 2s, 4s, 8s...
- **熔断机制**：连续失败N次后停止
- **降级策略**：切换到备用资源

**配置示例**：
```yaml
retry_policy:
  max_attempts: 3
  backoff: exponential
  initial_delay: 1s
  max_delay: 60s
  circuit_breaker:
    failure_threshold: 5
    timeout: 30s
```

### Module 5: 实时监控面板
**指标体系**：
- **系统级**：CPU、内存、网络
- **业务级**：任务成功率、平均执行时间
- **资源级**：GPU利用率、队列长度

**技术栈**：
- Prometheus（指标采集）
- Grafana（可视化）
- WebSocket（实时推送）

### Module 6: 插件化执行器
**支持类型**：
```java
interface Executor {
    Result execute(Task task);
}

class ShellExecutor implements Executor { ... }
class PythonExecutor implements Executor { ... }
class DockerExecutor implements Executor { ... }
class K8sJobExecutor implements Executor { ... }
```

### Module 7: API限流与熔断
**工具选择**：
- **Guava RateLimiter**（单机）
- **Sentinel**（分布式）
- **Resilience4j**（熔断器）

**配置**：
```java
@RateLimiter(name = "submitTask", fallbackMethod = "rateLimitFallback")
@CircuitBreaker(name = "scheduler", fallbackMethod = "schedulerFallback")
public TaskInstance submitTask(Task task) { ... }
```

---

## 🧪 性能优化与测试

### 压力测试目标
| 指标 | 目标值 | 备注 |
|------|--------|------|
| 任务提交QPS | 1000+ | Redis队列削峰 + 批量插入 |
| 调度延迟 | <100ms | 从提交到调度 |
| 资源分配延迟 | <50ms | 匹配算法优化 + Redis缓存 |
| 并发执行任务数 | 10000+ | 线程池调优 |
| 数据库查询 | <10ms | 索引 + Redis缓存 |
| Redis响应时间 | <5ms | P99延迟 |
| 缓存命中率 | >80% | 热点数据缓存 |

### 性能优化手段
1. **数据库层**：
   - 批量插入（JDBC Batch）
   - 读写分离
   - 连接池调优（HikariCP）

2. **缓存层**：
   - **Redis缓存热点数据**
     - 用户信息缓存（TTL 30分钟）
     - 项目配置缓存（TTL 10分钟）
     - 资源节点状态缓存（TTL 1分钟）
     - 任务执行统计缓存（TTL 5分钟）
   - **本地缓存（Caffeine）**
     - 用户会话信息（10000条，LRU淘汰）
     - 配置信息（热加载）
   - **缓存策略**
     - Cache-Aside模式（旁路缓存）
     - 缓存穿透防护（布隆过滤器）
     - 缓存雪崩防护（随机TTL）
     - 缓存击穿防护（分布式锁+双重检查）
   - **缓存预热**
     - 系统启动时加载活跃项目
     - 加载所有资源节点信息

3. **异步处理**：
   - **Redis削峰队列**（可选MQ替代方案）
     - 基于List的任务提交队列（LPUSH/BRPOP）
     - 基于Sorted Set的优先级队列（按优先级+等待时间排序）
     - 基于Sorted Set的延迟队列（按执行时间排序）
   - **MQ削峰**（生产环境推荐RabbitMQ/Kafka）
   - **线程池隔离**
     - 任务提交线程池
     - 任务调度线程池
     - 任务执行线程池
   - **CompletableFuture**
     - 异步任务编排
     - DAG并行执行

---

## 📦 技术栈选型

### 后端核心
- **框架**：Spring Boot 3.x + Spring Cloud Alibaba
- **ORM**：MyBatis-Plus（自动CRUD） + Liquibase（版本管理）
- **分布式**：
  - **Redisson 3.23+**（分布式锁、分布式数据结构）
    - RLock（可重入锁）
    - RReadWriteLock（读写锁）
    - RSemaphore（信号量）
    - RCountDownLatch（倒计数器）
    - RBloomFilter（布隆过滤器）
  - **Spring Data Redis 3.1+**（缓存、队列）
    - RedisTemplate（基础操作）
    - StringRedisTemplate（字符串操作）
    - RedisMessageListenerContainer（Pub/Sub）
  - Curator（ZooKeeper客户端，可选）
- **Redis**：
  - **版本**：Redis 7.0+
  - **客户端**：Lettuce（异步、响应式）
  - **部署模式**：
    - 开发环境：单机模式
    - 生产环境：哨兵模式/集群模式
  - **核心应用**：
    - 分布式锁（Leader选举、并发控制）
    - 多层缓存（热点数据、查询结果）
    - 消息队列（削峰、延迟任务）
    - 计数器（限流、统计）
    - 去重（任务去重、幂等性）
- **消息队列**：RabbitMQ / Kafka（大规模场景）
- **监控**：Micrometer + Prometheus

### 前端（可选）
- **框架**：Vue 3 + Element Plus / React + Ant Design
- **可视化**：ECharts / G6（图编排）
- **实时通信**：SockJS + STOMP

### 基础设施
- **容器化**：Docker + Docker Compose
- **CI/CD**：GitHub Actions / GitLab CI
- **文档**：Swagger/OpenAPI 3.0

---

## 🗓️ 迭代计划（Agile Roadmap）

### Sprint 0: 基础设施（3天）
- 项目脚手架搭建
- Docker环境配置
- 数据库ER图 + DDL

### Sprint 1: 核心功能（5天）
- 任务CRUD + 状态机
- 简单调度器（FIFO）
- Worker执行器（Shell）

### Sprint 2: 资源调度（4天）
- 资源节点管理
- 资源分配算法
- 并发控制

### Sprint 3: DAG工作流（5天）
- 依赖关系建模
- 拓扑排序引擎
- 并行执行逻辑

### Sprint 4: 高级特性（5天）
- 多租户隔离
- 优先级调度
- 任务重试

### Sprint 5: 监控告警（4天）
- 指标采集
- Grafana面板
- 告警规则

### Sprint 6: 前端界面（5天）
- 任务管理页面
- 工作流编排器
- 实时监控大屏

### Sprint 7: 性能优化（3天）
- 压力测试
- SQL优化
- 缓存优化

### Sprint 8: 文档交付（2天）
- 架构文档
- API文档
- 部署指南

---

## 📝 作业交付清单

### 必须交付
- [ ] ER图（10+实体，7+关系）
- [ ] 规范化分析（1NF → 3NF）
- [ ] 完整DDL + 10个索引
- [ ] 10个复杂查询（JOIN/GROUP BY/子查询）
- [ ] 索引性能对比实验

### 加分项
- [ ] 视图设计（5个）
- [ ] 存储过程（3个）
- [ ] 触发器（2个）
- [ ] 事务隔离实验
- [ ] 分布式事务案例

### 代码交付
- [ ] GitHub仓库（结构化README）
- [ ] Docker一键部署
- [ ] Postman API文档
- [ ] 单元测试覆盖率 > 60%

---

## 🎓 简历可写技能点

完成本项目后，你可以在简历中展示：

### 技术栈
- 分布式系统设计
- 高并发任务调度（1000+ QPS）
- 资源管理算法（Best Fit、DRF）
- DAG工作流引擎（拓扑排序）
- 数据库性能优化（索引、批量操作）
- **Redis分布式技术**
  - 分布式锁（Redisson）
  - 多层缓存架构
  - 消息队列（List/Sorted Set）
  - 布隆过滤器防缓存穿透
- Docker容器化部署

### 可量化成果
- 支持**1000+ QPS**任务提交（Redis队列削峰）
- 管理**10000+**并发任务
- 实现**3种**调度算法（FIFO、优先级、公平调度）
- 优化SQL查询**从2s降至20ms**（索引 + Redis缓存）
- 缓存命中率达**85%+**（多层缓存架构）
- Redis分布式锁保证**零重复调度**
- 设计**12个实体**的复杂ER模型

---

## 🔥 面试可深挖话题

1. **系统设计**：
   - "如何保证任务不被重复执行？"
   - "如何实现任务的公平调度？"
   
2. **性能优化**：
   - "如何优化高并发下的数据库写入？"
   - "如何减少任务调度的延迟？"

3. **分布式**：
   - "如何实现Scheduler的高可用？"
     - **答**：使用Redis分布式锁实现Leader选举，多个Scheduler实例同时运行但只有一个Leader执行调度，Leader宕机后其他实例自动竞选成为新Leader
   - "如何处理分布式环境下的资源竞争？"
     - **答**：对资源节点加Redis分布式锁，任务分配时先锁定节点，分配完成后释放锁，保证同一时刻只有一个调度器能修改节点资源
   - "Redis分布式锁如何防止死锁？"
     - **答**：设置锁超时时间（30秒），使用Redisson的Watch Dog自动续期，业务完成后主动释放锁，避免长时间占用

4. **算法**：
   - "DAG拓扑排序的实现？"
   - "资源装箱算法的选择？"

---

## 🚧 已知限制与未来扩展

### 当前限制
- 单数据库（未分库分表）
- 无跨地域调度
- 日志存储有限

### 未来扩展方向
- Kubernetes原生调度器
- GPU虚拟化支持
- 联邦学习任务编排
- WASM插件系统

---

## 🎯 总结

这不是一个玩具项目，而是一个：
- **可落地**：真实场景可用
- **可扩展**：架构支持演进
- **可展示**：简历 + 面试素材
- **可学习**：涵盖分布式核心知识

**预计开发时间**：25-30天（全职） / 40-50天（兼职）

**技术难度**：⭐⭐⭐⭐☆（中高级）

**简历价值**：⭐⭐⭐⭐⭐（非常高）
