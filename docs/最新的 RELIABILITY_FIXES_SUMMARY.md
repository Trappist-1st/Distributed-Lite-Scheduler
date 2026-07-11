# 系统可靠性修缮总结文档

**版本**：V2  
**修缮范围**：经面试级深度分析后识别的 8 类核心问题  
**修改文件**：7 个现有文件 + 2 个新增文件  

---

## 一、背景与问题全景

本次修缮基于对系统代码的逐层分析，识别出以下 8 类问题，按严重程度排列：

| # | 问题类别 | 严重程度 | 核心文件 |
|---|---------|---------|---------|
| 1 | 内存队列数据丢失 | **致命** | `TaskSubmitServiceImpl` |
| 2 | DAG 推进非幂等（重复消费） | **致命** | `WorkflowLayerDispatchFacade` + `TaskCompletionStreamHandler` |
| 3 | Redis Stream 消费者宕机后 DAG 卡死 | **致命** | `TaskCompletionRedisStreamConfig` |
| 4 | 孤儿 RESERVED 资源（资源泄漏） | **高** | `ResourceSlotServiceImpl` |
| 5 | 调度器 N+1 查询（性能瓶颈） | **高** | `ResourceAwareSchedulerServiceImpl` |
| 6 | 缓存击穿（进程内无锁） | **中** | `DistributedWorkflowCacheManager` |
| 7 | 卡死工作流无兜底恢复 | **中** | 无（新增） |
| 8 | DISPATCHED 状态缺失（枚举不完整） | **低** | `TaskInstanceStatus` |

---

## 二、各问题详述与修复方案

---

### 问题 1：内存队列带来的"确认了但会丢失"的数据丢失窗口

#### 根本原因

原 `TaskSubmitServiceImpl` 使用 `LinkedBlockingQueue<TaskSubmitRequest>` 做削峰缓冲：

```java
// 原代码（已删除）
boolean isSubmitted = taskSubmitQueue.offer(request, 200, TimeUnit.MILLISECONDS);
// 此处返回 200 OK 给调用方 ↓
// 但 @Scheduled batchConsume() 还没执行
// ← JVM crash 在这里 → 任务彻底丢失
```

这制造了一个"语义撒谎"：调用方收到成功响应，但任务尚未落库。`handleBatchInsertFailure` 里的 `lostCount` 日志更是明确承认了数据丢失的存在。

#### 修复方案

**文件**：`TaskSubmitServiceImpl.java`  
**策略**：彻底删除内存队列，`submitTask` 与 `submitBatch` 改为同步 `@Transactional` 写入。

```java
@Override
@Transactional(rollbackFor = Exception.class)
public Result<TaskSubmitResponse> submitTask(TaskSubmitRequest request) {
    // ... 校验 ...
    TaskInstance taskInstance = toTaskInstance(request);
    taskInstanceMapper.insert(taskInstance); // ← 同事务，成功即落库
    return Result.success(...);
}
```

**效果**：调用方收到 `200 OK` 时，任务已 100% 落库，不存在"已确认但未持久化"的悬空窗口。  
**代价**：去掉了削峰能力（原本的削峰效果有限，且代价是数据不可靠）。若后续需要高吞吐削峰，应接入 MQ（Kafka/RocketMQ）并用 DB `PENDING` 状态做对账。

---

### 问题 2：DAG 推进非幂等（重复消费触发两次层推进）

#### 根本原因

**路径一**：`WorkflowLayerDispatchFacade.dispatchLayer()` 在提交任务成功后，只更新了 `taskInstanceId`，但 `workflow_task_instance.status` 仍然是 `PENDING`。

```java
// 原代码（有问题）
wtInstance.setTaskInstanceId(submitResponse.getTaskInstanceId());
workflowTaskInstanceMapper.updateById(wtInstance); // status 仍是 PENDING
```

后果：如果 `dispatchLayer()` 因任何原因被重入（reconciliation 补偿、重试逻辑），同一个任务会被重复提交到调度器，产生两个 `task_instance`，DAG 的完成计数器被双计。

**路径二**：`TaskCompletionStreamHandler.handle()` 没有入口幂等检查。如果 Redis Stream 重放（consumer group reset、lag 导致重复消费、手动 replay），同一个事件被处理两次：
- `incrementCompletedTasks` 被调用两次 → 完成计数器被双计
- `dispatchLayer(nextLayer)` 被调用两次 → 下一层任务被重复提交

#### 修复方案

**修复一**：`WorkflowLayerDispatchFacade.java` — 提交成功后更新状态为 `DISPATCHED`（新增状态，详见问题 8）

```java
// 修复后
wtInstance.setTaskInstanceId(submitResponse.getTaskInstanceId());
wtInstance.setStatus(TaskInstanceStatus.DISPATCHED.getCode()); // 关键屏障
workflowTaskInstanceMapper.updateById(wtInstance);
```

**修复二**：`TaskCompletionStreamHandler.java` — handler 入口加幂等检查

```java
// 修复后：在处理每个 workflow_task_instance 之前检查其当前状态
TaskInstanceStatus currentStatus = TaskInstanceStatus.fromCode(wtInstance.getStatus());
if (currentStatus.isTerminal()) {
    log.info("幂等跳过：workflow_task_instance 已为终态，忽略重复事件 ...");
    continue; // 直接跳过，不重新处理
}
```

**幂等保护的双重防线**：
1. `workflow_task_instance.status = DISPATCHED` → 防止 `dispatchLayer` 重入时重复提交
2. `workflow_task_instance.status = terminal` → 防止 completion 事件重放时重复推进

---

### 问题 3：Redis Stream 消费者宕机后 DAG 永久卡死

#### 根本原因

`TaskCompletionRedisStreamConfig` 使用了随机 UUID 作为消费者名：

```java
// 原代码（已修复）
return CONSUMER_NAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
```

Redis Stream 的消费者组语义：消息被 deliver 给消费者后进入该消费者的 PEL（Pending Entry List），只有在 `XACK` 之后才从 PEL 移除。

如果消费者宕机，使用 UUID 命名意味着：
- 新实例启动后 → 生成新的 UUID → 全新的消费者身份 → 旧 PEL 中的未 ACK 消息无人认领
- `ReadOffset.lastConsumed()` 只读 `>` 即只接收新消息，不读 PEL
- 结果：宕机前正在处理的 completion event 永久"卡"在 PEL 里，DAG 层推进事件丢失，工作流永远不会完成

#### 修复方案

**文件**：`TaskCompletionRedisStreamConfig.java`

**修复一**：使用稳定消费者名（hostname + PID，K8s 环境优先用 `POD_NAME`）

```java
String podName = System.getenv("POD_NAME");
if (podName != null && !podName.isBlank()) {
    return CONSUMER_NAME_PREFIX + podName; // K8s: dag-consumer-scheduler-pod-0
}
String hostname = InetAddress.getLocalHost().getHostName();
String pid = ProcessHandle.current().pid() + "";
return CONSUMER_NAME_PREFIX + hostname + "-" + pid; // 开发环境: dag-consumer-myhost-12345
```

**修复二**：启动时主动认领 PEL 中空闲超 30 秒的孤儿消息（`XCLAIM`）

```java
private void reclaimOrphanedPelMessages(StringRedisTemplate stringRedisTemplate, String consumerName) {
    PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
            .pending(STREAM_KEY, CONSUMER_GROUP, Range.unbounded(), 100L);
    for (PendingMessage msg : pendingMessages) {
        if (msg.getElapsedTimeSinceLastDelivery().toSeconds() >= 30) {
            stringRedisTemplate.opsForStream().claim(
                STREAM_KEY, CONSUMER_GROUP, consumerName, Duration.ofSeconds(30), msg.getId());
        }
    }
}
```

**配合幂等检查**：因为 XCLAIM 可能导致消息被重新投递给当前消费者，handler 的幂等检查（问题 2 修复）确保重复处理不产生副作用。

---

### 问题 4：孤儿 RESERVED 资源（资源泄漏）

#### 根本原因

`ResourceSlotServiceImpl.reserve()` 是一个 `@Transactional` 方法，commit 后 `ResourceAwareSchedulerServiceImpl.finalizeDispatch()` 才调用 `updateStatusWithVersion()`：

```java
// finalizeDispatch 伪代码（非事务整体）
reservedUsageId = reserveResource(latest, node);  // ← 事务提交
// ← 如果 JVM crash 在这里，resource_usage.status=RESERVED 但 task_instance.status 仍是 PENDING
int updated = taskInstanceMapper.updateStatusWithVersion(...); // PENDING → RUNNING
```

此时孤儿 `resource_usage` 记录永久占用节点槽位，且 `task_instance` 也永远无法被调度（因为 `resourceNodeId` 被绑定了，但状态仍是 PENDING）。

#### 修复方案

**新增 `ReconciliationWorker.recoverOrphanedReservations()`**（见问题 7）

同时新增 `ResourceUsageMapper.selectOrphanedReserved()`：

```sql
SELECT ru.* FROM resource_usage ru
LEFT JOIN task_instance ti ON ti.id = ru.task_instance_id
WHERE ru.status = 'RESERVED'
AND (ti.id IS NULL OR ti.status != 'RUNNING')
LIMIT #{limit}
```

修复逻辑：
1. 调用 `releaseForTaskInstanceSystem()` 释放 usage、归还槽位和配额
2. 将 `task_instance.resource_node_id` 清空，使其重新进入可调度队列

**理想方案（后续演进方向）**：为 `resource_usage` 增加 `lease_expire_at` 字段，变"永久预留"为"租约预留"。到期未确认的 RESERVED 自动视为失效，无需 ReconciliationWorker 介入。

---

### 问题 5：调度器 N+1 查询

#### 根本原因

`ResourceAwareSchedulerServiceImpl.scheduleLoop()` 原先对每一个待调度任务都调用一次 `listOnlineNodes()`：

```java
// 原代码（BATCH_SIZE=100 → 100次全表扫）
for (TaskWithPriority twp : tasks) {
    ...
    List<ResourceNode> onlineNodes = listOnlineNodes(); // 每个任务都查一次
    ...
}
```

在 `BATCH_SIZE=100` 时，每轮调度触发 100 次 `SELECT * FROM resource_node WHERE status='ONLINE'`，对数据库造成不必要的读压力，同时调度延迟随任务数线性增长。

#### 修复方案

**文件**：`ResourceAwareSchedulerServiceImpl.java`

将 `listOnlineNodes()` 调用**提到循环外部**，整轮共享一次节点快照：

```java
@Override
public void scheduleLoop() {
    List<TaskWithPriority> tasks = scanPendingTasksWithPriority(BATCH_SIZE);
    if (tasks.isEmpty()) return;
    
    List<ResourceNode> onlineNodes = listOnlineNodes(); // ← 整轮只调用一次
    if (onlineNodes.isEmpty()) { ... return; }
    
    for (TaskWithPriority twp : tasks) {
        scheduleTask(t, onlineNodes); // ← 复用预加载的节点列表
    }
}
```

新增私有重载方法 `scheduleTask(TaskInstance, List<ResourceNode>)` 接受预加载节点列表，原有 `scheduleTask(TaskInstance)` 作为接口兼容方法保留，内部调用新方法。

**效果**：N+1 → 1 次查询。100 个任务场景下，节点查询从 100 次降为 1 次。

**进一步优化方向**：可将 `listOnlineNodes()` 的结果写入 Redis 缓存（TTL 30s），彻底消除节点查询对 MySQL 的压力，节点注册/注销时 publish 失效通知。

---

### 问题 6：缓存击穿（进程内无锁，1000 并发穿透 DB）

#### 根本原因

`DistributedWorkflowCacheManager.getExecutionPlan()` 使用 `localCache.getIfPresent()`：

```java
// 原代码
WorkflowExecutionPlan plan = localCache.getIfPresent(workflowId); // 无锁
if (plan != null) return plan;
// ← 缓存过期瞬间，1000个并发线程全部从这里穿透
plan = loader.apply(workflowId); // 1000次并发查 MySQL
```

Caffeine 的 `getIfPresent()` 不提供 per-key 互斥语义，对于同一个 key 同时多个线程都会拿到 null，从而并发执行 `loader`。

#### 修复方案

**文件**：`DistributedWorkflowCacheManager.java`

使用 Caffeine 的 `cache.get(key, mappingFunction)` 代替 `getIfPresent()`：

```java
return localCache.get(workflowId, id -> loadFromRedisOrDb(id, loader));
```

`Caffeine.get(key, loader)` 的语义：同一个 key 在并发时只有**一个线程**执行 `mappingFunction`，其余线程**阻塞等待**并复用结果，天然实现了进程内 per-key 互斥，彻底消灭进程内缓存击穿。

同时将原 `getExecutionPlan()` 中的多段 if-else 重构为独立的 `loadFromRedisOrDb()` 方法，职责更清晰。

**跨 JVM 的击穿**：多实例场景下，同一时刻多个实例都在 L1 miss 时会各自查 Redis（L2）。如果 Redis 也 miss，多实例会并发查 DB。对于这个场景，可以通过 Redisson 分布式锁在 `loadFromRedisOrDb()` 中加保护（进一步演进方向，当前单次 DB 读 + 相同结果写入是可接受的）。

---

### 问题 7：系统无全局对账/恢复兜底

#### 根本原因

各组件（调度器、Stream 消费者、资源管理器）都有自己的错误处理，但没有一个统一的"外部观察者"从 DB 全局视角来发现和修复各种中间态。典型场景：

- JVM crash 后留下的孤儿 RESERVED（问题 4）
- Redis Stream 消费者宕机后的卡死 DAG（问题 3 修复了预防，但历史遗留实例需清理）
- TaskTimeoutWatchdog 本身宕机期间积累的超时 RUNNING 任务

#### 修复方案

**新增文件**：`ReconciliationWorker.java`

每 60 秒执行一次，**仅 Leader 节点运行**，扫描以下三类异常状态：

**扫描一：孤儿 RESERVED 资源**
```
SELECT resource_usage WHERE status='RESERVED'
  AND (task_instance 不存在 OR task_instance.status != 'RUNNING')
```
修复：释放资源，清空 task_instance.resource_node_id

**扫描二：超时 RUNNING task_instance**
```
SELECT task_instance JOIN task WHERE status='RUNNING'
  AND 已运行时间 > task.timeout_seconds
```
修复：CAS 更新为 TIMEOUT，释放资源

**扫描三：卡死 RUNNING workflow_instance**
```
SELECT workflow_instance WHERE status='RUNNING'
  + 判断所有子任务是否均已终态
  + 判断下一层是否存在未触发的 PENDING 任务
```
修复：重新调用 `onWorkflowTaskTerminated()` 触发层推进

```java
@Scheduled(fixedDelay = 60_000)
public void reconcile() {
    if (!schedulerLeaderElection.isLeader()) return; // 仅 Leader 执行
    recoverOrphanedReservations();
    recoverTimedOutRunningTasks();
    recoverStuckWorkflowInstances();
}
```

---

### 问题 8：DISPATCHED 状态缺失

#### 根本原因

`TaskInstanceStatus` 枚举只有 `PENDING → RUNNING → terminal` 的流转，缺少"已提交给调度器、等待被调度"的中间状态 `DISPATCHED`，导致 `WorkflowLayerDispatchFacade` 无法区分"未提交"和"已提交"的任务。

#### 修复方案

**文件**：`TaskInstanceStatus.java`

新增枚举值：

```java
DISPATCHED("DISPATCHED", "已提交调度器"),
```

状态流转变为：
```
PENDING → DISPATCHED → RUNNING → SUCCESS / FAILED / TIMEOUT / CANCELLED
                   ↗
           (workflow task)
```

- `DISPATCHED` 在 `isTerminal()` 中返回 `false`（正确，任务尚未完成）
- 作为 `WorkflowLayerDispatchFacade` 防重提交屏障：下次 `dispatchLayer` 重入时，看到 `DISPATCHED` 就跳过，不会重复提交
- 作为 `TaskCompletionStreamHandler` 幂等检查的配合状态：`DISPATCHED` 不是终态，所以正常事件会继续处理；处理完后变为终态，重放的事件被拦截

---

## 三、修改文件清单

| 文件路径 | 改动类型 | 改动摘要 |
|---------|---------|---------|
| `constant/TaskInstanceStatus.java` | 修改 | 新增 `DISPATCHED` 枚举值 |
| `service/impl/TaskSubmitServiceImpl.java` | 重写 | 删除内存队列，同步事务写入 |
| `service/scheduler/impl/ResourceAwareSchedulerServiceImpl.java` | 修改 | `listOnlineNodes()` 提到循环外，新增内部重载方法 |
| `service/workflow/impl/WorkflowLayerDispatchFacade.java` | 修改 | 提交成功后设置 `status=DISPATCHED` |
| `service/workflow/stream/TaskCompletionStreamHandler.java` | 修改 | handler 入口加终态幂等检查 |
| `config/TaskCompletionRedisStreamConfig.java` | 修改 | 稳定消费者名，启动时 XCLAIM 认领 PEL |
| `service/workflow/cache/DistributedWorkflowCacheManager.java` | 修改 | `getIfPresent` 换为 Caffeine `get(key, loader)` |
| `mapper/ResourceUsageMapper.java` | 修改 | 新增 `selectOrphanedReserved()` 查询 |
| `mapper/WorkflowInstanceMapper.java` | 修改 | 新增 `selectRunningInstances()` 查询 |
| `service/scheduler/ReconciliationWorker.java` | 新增 | 全局对账 Worker（三类异常态扫描） |

---

## 四、未做的改动与说明

以下问题被识别但未在本次代码改动中实现，原因是需要数据库 DDL 变更或更大的架构调整：

### 4.1 资源 Lease 模型（reservation → lease with TTL）

在 `resource_usage` 表中增加 `lease_expire_at DATETIME` 字段，并用 `@Scheduled` 的 LeaseGC Worker 定期扫描和回收过期 Lease，可从根本上解决问题 4，不再依赖 ReconciliationWorker 做事后补偿。

**需要的 DDL**：
```sql
ALTER TABLE resource_usage ADD COLUMN lease_expire_at DATETIME COMMENT 'RESERVED状态的租约过期时间';
CREATE INDEX idx_resource_usage_lease ON resource_usage(status, lease_expire_at);
```

### 4.2 Redisson RLock 双 Leader 问题

目前使用标准 `RLock` 在单 Redis Master 上做 Leader 选举。Redis 主从切换时存在"双 Leader 时间窗口"（节点 A 持有旧锁，节点 B 在新 Master 上重新获锁）。

**现有保护**：MySQL CAS（`updateStatusWithVersion`）作为最终安全网，即使双 Leader 并发调度同一任务，只有一方的 CAS 能成功。这是当前系统可接受的设计，不做改动。

**完整解法**：使用 Redisson `RedLock`（需要 2N+1 个 Redis 节点），或引入 Fencing Token（锁 epoch 写入 DB，CAS 时校验 epoch）。

### 4.3 调度器 100k QPS 扩展

当前调度器是单 Leader 模型，吞吐上限约为单机处理 `BATCH_SIZE` 任务/轮。扩展到 100k QPS 需要：
- 按 `tenantId % N` 分片，每个分片独立 Leader 选举和调度队列
- 引入独立的 `pending task` 分片表，减少 `task_instance` 全表扫 PENDING 的锁竞争
- 节点状态缓存到 Redis（已在 Fix 5 中提及为后续方向）

---

## 五、问题与修复的因果图

```
JVM crash 场景
├── 内存队列 offer() 后 crash → [Fix 1] 同步事务写入
├── reserve() 后 crash → [Fix 4+7] ReconciliationWorker 清理孤儿 RESERVED
└── Stream 消费者 crash
    ├── UUID 名字 → PEL 孤儿 → DAG 卡死 → [Fix 3] 稳定名 + XCLAIM
    └── 消息已被 handle 但未 ACK → 重复消费 → [Fix 2] 幂等检查

并发/重放场景
├── dispatchLayer 重入 → 重复提交任务 → [Fix 2] DISPATCHED 屏障
├── completion event replay → DAG 双计 → [Fix 2] 终态幂等检查
└── 1000 并发缓存 miss → DB 打爆 → [Fix 6] Caffeine get(key, loader)

性能场景
└── 每任务查一次节点 → N+1 → [Fix 5] 循环外预加载

状态机完整性
└── PENDING→RUNNING 中间无状态 → [Fix 8] 新增 DISPATCHED
```

---

## 六、验证建议

| 修复 | 验证方式 |
|-----|---------|
| Fix 1 内存队列 | kill -9 进程，确认之前提交的任务在 DB 中可查 |
| Fix 2 DAG 幂等 | 手动 RESET consumer group offset 后检查工作流实例状态不重复推进 |
| Fix 3 消费者恢复 | kill 消费者，10 秒后重启，检查日志中出现 "成功认领孤儿 PEL 消息" |
| Fix 4+7 孤儿资源 | 制造孤儿 RESERVED（手动插入），等待 ReconciliationWorker 扫描日志 |
| Fix 5 N+1 | 开启 MySQL general_log，观察一轮调度中 `resource_node` 的查询次数 |
| Fix 6 缓存击穿 | JMeter 1000 并发请求同一 workflow，缓存过期时观察 DB 连接数 |
