# 工作流系统架构职责分工说明

## ⭐⭐⭐ 核心原则

```
DAG引擎 不做 资源管理
DAG引擎 只做 任务顺序编排
DAG引擎 必须通过 调度器 来提交任务

调度器职责：选点、配额、预留、流转状态、提交执行
执行器职责：具体运行、监控、回调完成
DAG引擎职责：拓扑、条件、顺序、下一批计算
```

---

## 1. 三层架构职责分工

### 第1层：DAG工作流引擎 (DAGExecutionEngine / WorkflowExecutor)

#### 职责：处理工作流逻辑

```
✅ DAG引擎负责：
• 解析DAG拓扑
• 计算就绪任务（入度为0或上游完成）
• 验证条件表达式（if any）
• 创建 TaskInstance(status=PENDING)
• 提交就绪任务给调度器（通过TaskSubmitService）
• 监听任务完成事件（通过EventListener）
• 计算下一批就绪任务
• 标记工作流完成或失败

❌ DAG引擎不负责：
• 配额检查 → 调度器职责
• 节点选择 → 调度器职责
• 资源预留 → 调度器职责
• 状态流转 PENDING→RUNNING → 调度器职责
• 提交执行器 → 调度器职责
```

#### 实现类
- `WorkflowExecutorImpl`: 工作流执行引擎
- `TaskCompletionEventListener`: 任务完成事件监听器

#### 关键方法
```java
// 执行工作流实例
void executeWorkflowInstance(Long instanceId);

// 提交指定层的任务（只创建PENDING任务）
private void submitLayerTasks(Long instanceId, int layerIndex, Long submitUserId);

// 监听任务完成事件
void processTaskCompletion(WorkflowTaskInstance wtInstance);

// 检查并提交下一层任务
private void checkAndSubmitNextLayer(Long instanceId, int currentLayer);
```

---

### 第2层：调度器 (Scheduler)

#### 职责：调度决策（选择和管理资源）

```
✅ 调度器负责：
• 从队列取出 TaskInstance(PENDING)
• 检查租户资源配额
• 选择目标资源节点
• 预留资源槽位
• 更新 TaskInstance 状态 PENDING → RUNNING
• 绑定资源节点ID
• 提交到执行器
• 发布任务启动事件

❌ 调度器不负责：
• DAG拓扑解析 → DAG引擎职责
• 计算任务依赖 → DAG引擎职责
• 工作流状态管理 → DAG引擎职责
```

#### 实现类
- `FifoSchedulerServiceImpl`: FIFO调度器
- `PrioritySchedulerServiceImpl`: 优先级调度器
- `ResourceAwareSchedulerServiceImpl`: 资源感知调度器

#### 关键方法
```java
// 调度主循环
void scheduleLoop();

// 调度单个任务
boolean scheduleTask(TaskInstance task);

// 扫描待调度任务
List<TaskInstance> scanPendingTasks(int limit);

// 配额检查
private boolean checkQuota(TaskInstance task);

// 节点选择
private ResourceNode selectNode(TaskInstance task);

// 资源预留
private Long reserveResource(TaskInstance task, ResourceNode node);
```

---

### 第3层：执行器 (Executor/Worker)

#### 职责：具体执行

```
✅ 执行器负责：
• 接收 TaskInstance
• 在绑定的资源节点上运行任务
• 监控任务运行进度
• 捕获执行结果（exitCode、output、error）
• 更新 TaskInstance 状态 RUNNING → SUCCESS/FAILED
• 回调系统，发布 TaskCompletionEvent

❌ 执行器不负责：
• 资源选择 → 调度器职责
• 配额管理 → 调度器职责
• 工作流编排 → DAG引擎职责
```

#### 实现（待开发）
- Worker服务：接收任务并执行
- 任务回调接口：更新任务状态

---

## 2. 完整数据流示例

### 时刻1: 工作流启动

```
用户触发工作流执行
    ↓
WorkflowExecutor.executeWorkflowInstance(instanceId)
    ↓
1. 验证实例状态（必须是PENDING）
2. 更新状态为PREPARING
3. 解析执行计划JSON
4. 更新状态为RUNNING，记录开始时间
5. 提交第0层（入度为0）任务到调度器
    ↓
submitLayerTasks(instanceId, layerIndex=0, submitUserId)
    ↓
for each task in layer 0:
    1. 创建 WorkflowTaskInstance(status=PENDING)
    2. 转换为 TaskInstance(status=PENDING)
       taskInstance = wtInstance.toTaskInstance(tenantId, userId)
    3. 构建 TaskSubmitRequest
       request = buildTaskSubmitRequest(taskInstance)
    4. 提交到调度器队列
       taskSubmitService.submitTask(request)
       → 任务进入 TaskSubmitQueue（削峰队列）
```

### 时刻2: 调度器处理

```
调度器主循环（FifoSchedulerService.scheduleLoop()）
    ↓
1. 扫描待调度任务（从TaskSubmitQueue队列）
   pendingTasks = scanPendingTasks(batchSize)
    ↓
2. 逐个任务执行调度
   for each task in pendingTasks:
       scheduleTask(task)
           ↓
           a. 获取任务锁（防止并发调度）
           b. 检查配额
              checkQuota(task) → 调用 ResourceQuotaService
           c. 选择节点
              node = selectNode(task) → 选择ONLINE节点
           d. 预留资源
              usageId = reserveResource(task, node) → 调用 ResourceSlotService
           e. 更新状态为RUNNING
              taskInstanceMapper.updateStatusWithVersion(
                  taskId, PENDING, RUNNING, version, nodeId, scheduledTime, startTime
              )
           f. 提交到执行器
              submitToExecutor(task, node) → 发送到远程Worker
           g. 发布任务启动事件
              publishTaskStartedEvent(task)
```

### 时刻3: 执行器执行

```
远程执行器/Worker
    ↓
1. 接收 TaskInstance（状态=RUNNING）
2. 在容器/进程中运行任务
3. 监控执行进度
4. 捕获执行结果
   exitCode: 0
   output: {...}
5. 回调系统
   PUT /api/task-instance/{taskInstanceId}/complete
   {
       status: SUCCESS,
       exitCode: 0,
       output: {...},
       endTime: "2026-05-14T20:30:00"
   }
    ↓
TaskInstanceController.completeTask(taskInstanceId, request)
    ↓
1. 更新 TaskInstance 状态 RUNNING → SUCCESS
2. 发布 TaskCompletionEvent
   Redis Stream: task-completion
   {
       taskInstanceId: 1001,
       workflowInstanceId: 5000,
       status: SUCCESS,
       exitCode: 0,
       output: {...}
   }
```

### 时刻4: DAG引擎处理完成事件

```
TaskCompletionEventListener.scanCompletedTasks()
    ↓
1. 扫描所有RUNNING状态的WorkflowTaskInstance
2. 检查对应的TaskInstance是否已完成（SUCCESS/FAILED）
    ↓
processTaskCompletion(wtInstance)
    ↓
3. 更新 WorkflowTaskInstance 状态
   wtInstance.setStatus(SUCCESS)
   wtInstance.setEndTime(now)
   wtInstance.setExitCode(0)
   workflowTaskInstanceMapper.updateById(wtInstance)
    ↓
4. 更新工作流实例完成计数
   workflowInstanceMapper.incrementCompletedTasks(instanceId)
    ↓
5. 检查是否可以提交下一层任务
   checkAndSubmitNextLayer(instanceId, currentLayer)
       ↓
       a. 检查当前层所有任务是否都已完成
       b. 如果当前层完成，提交下一层任务
          submitLayerTasks(instanceId, nextLayer, submitUserId)
       c. 如果是最后一层，标记工作流完成
          completeWorkflowInstance(instance)
```

---

## 3. 关键区别对比表

| 环节 | DAG引擎 | 调度器 | 执行器 |
|------|--------|--------|--------|
| **输入** | Workflow定义 + ExecutionPlan | TaskInstance(PENDING) | TaskInstance(RUNNING) + ResourceNode |
| **输出** | WorkflowInstance(SUCCESS/FAILED) | TaskInstance(RUNNING) | TaskCompletionEvent |
| **关键决策** | 下一个任务是谁？ | 下一个节点是哪个？ | 怎么执行这个任务？ |
| **资源管理** | ❌ 不管 | ✅ 配额、预留、选点 | ✅ 实际分配和释放 |
| **状态转变** | PENDING创建 | PENDING→RUNNING | RUNNING→SUCCESS/FAILED |
| **依赖处理** | ✅ 计算就绪任务 | ❌ 不管 | ❌ 不管 |
| **并发控制** | ✅ 层级并行 | ✅ 调度并发 | ✅ 执行并发 |

---

## 4. 错误示例：DAG引擎越权

### ❌ 错误代码（P4-3设计文档中的问题）

```java
// 错误：DAG引擎直接做了调度器的工作
private boolean executeTask(WorkflowInstance instance, WorkflowTaskInstance taskInstance) {
    // ❌ 这些都不应该在DAG引擎中
    checkQuota(task);              // ❌ 调度器的职责
    reserveResource(task, node);   // ❌ 调度器的职责
    selectNode(task);              // ❌ 调度器的职责
    submitToExecutor(task, node);  // ❌ 调度器的职责
    
    // ❌ 轮询等待任务完成（应该用事件机制）
    waitForTaskCompletion(taskInstanceId);
}
```

### ✅ 正确代码（重构后）

```java
// 正确：DAG引擎只做编排，提交给调度器
private void submitLayerTasks(Long instanceId, int layerIndex, Long submitUserId) {
    List<WorkflowTaskInstance> layerTasks = 
        workflowTaskInstanceMapper.selectByInstanceIdAndLayer(instanceId, layerIndex);
    
    for (WorkflowTaskInstance wtInstance : layerTasks) {
        // 1. 转换为TaskInstance（状态=PENDING）
        TaskInstance taskInstance = wtInstance.toTaskInstance(tenantId, submitUserId);
        
        // 2. 构建TaskSubmitRequest
        TaskSubmitRequest request = buildTaskSubmitRequest(taskInstance);
        
        // 3. 提交到调度器队列
        // 调度器会负责：配额检查、选点、预留、流转状态、提交执行
        taskSubmitService.submitTask(request);
    }
}
```

---

## 5. 架构优势

### 5.1 职责清晰
- **DAG引擎**：专注于工作流逻辑，不关心资源
- **调度器**：专注于资源管理，不关心工作流依赖
- **执行器**：专注于任务执行，不关心调度和编排

### 5.2 可扩展性
- **DAG引擎**：可以支持更复杂的编排逻辑（条件分支、循环等）
- **调度器**：可以实现多种调度策略（FIFO、优先级、资源感知等）
- **执行器**：可以支持多种执行方式（本地、容器、远程等）

### 5.3 可维护性
- 每层职责单一，修改一层不影响其他层
- 易于测试：每层可以独立测试
- 易于替换：例如替换调度算法不影响DAG引擎

### 5.4 性能优化
- **DAG引擎**：事件驱动，不阻塞线程
- **调度器**：批量处理，削峰填谷
- **执行器**：并行执行，资源隔离

---

## 6. 事件驱动架构

### 6.1 为什么不用轮询？

❌ **轮询方式的问题**：
```java
// 问题1：阻塞线程
while (true) {
    TaskInstance task = taskInstanceMapper.selectById(taskInstanceId);
    if (task.getStatus() == SUCCESS || task.getStatus() == FAILED) {
        break;
    }
    Thread.sleep(5000); // 阻塞5秒
}
// 这会导致：
// - 线程被阻塞，无法处理其他工作流
// - 轮询频繁，数据库压力大
// - 响应延迟（最多5秒）
```

✅ **事件驱动方式的优势**：
```java
// 优势1：异步非阻塞
@Scheduled(fixedDelay = 3000)
public void scanCompletedTasks() {
    List<WorkflowTaskInstance> runningTasks = 
        workflowTaskInstanceMapper.selectRunningTasks();
    
    for (WorkflowTaskInstance wtInstance : runningTasks) {
        // 异步处理
        processTaskCompletion(wtInstance);
    }
}
// 这会带来：
// - 一次扫描处理多个工作流
// - 数据库查询优化（批量查询）
// - 响应快速（3秒延迟）
```

### 6.2 事件流程图

```
TaskInstance完成
    ↓
TaskCompletionEventListener扫描
    ↓
更新WorkflowTaskInstance
    ↓
检查当前层是否全部完成
    ↓
提交下一层任务（如果有）
    ↓
循环...
```

---

## 7. 数据模型关系

```
Workflow (工作流定义)
    ↓ 1:N
WorkflowInstance (工作流实例)
    ↓ 1:N
WorkflowTaskInstance (工作流任务实例)
    ↓ 1:1 (转换)
TaskInstance (调度器任务实例)
    ↓ 1:1 (执行)
ExecutionResult (执行结果)
```

### 关键字段映射

| WorkflowTaskInstance | TaskInstance | 说明 |
|---------------------|--------------|------|
| id | - | 工作流任务实例ID |
| workflowInstanceId | workflowInstanceId | 所属工作流实例ID |
| taskName | - | 任务名称 |
| taskDefinition | parameters, executorConfig, resourceRequirement | 任务定义快照 |
| status | status | 执行状态（初始都是PENDING） |
| taskInstanceId | id | 关联的调度器任务实例ID |

---

## 8. 配置和启用

### 8.1 启用异步处理

```java
@Configuration
@EnableAsync
@EnableScheduling
public class WorkflowExecutorConfig {
    
    @Bean("workflowExecutorThreadPool")
    public ExecutorService workflowExecutorThreadPool() {
        return new ThreadPoolExecutor(
            10,  // 核心线程数
            20,  // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

### 8.2 监听器配置

```yaml
# application.yml
workflow:
  executor:
    # 任务完成扫描间隔（毫秒）
    scan-interval: 3000
    # 并行执行工作流实例数量
    max-parallel-instances: 50
```

---

## 9. 总结

### ⭐ 三层架构核心原则

```
┌────────────────────────────────────────────────────────┐
│  第1层：DAG引擎                                          │
│  ✓ 解析拓扑、计算就绪任务、提交PENDING任务                │
│  ✗ 不做资源管理决策                                      │
├────────────────────────────────────────────────────────┤
│  第2层：调度器                                           │
│  ✓ 配额检查、节点选择、资源预留、状态流转                 │
│  ✗ 不做工作流编排                                       │
├────────────────────────────────────────────────────────┤
│  第3层：执行器                                           │
│  ✓ 具体执行、监控、回调完成                              │
│  ✗ 不做资源选择和编排                                   │
└────────────────────────────────────────────────────────┘
```

### 🎯 关键实现文件

| 组件 | 文件路径 | 职责 |
|------|---------|------|
| DAG引擎 | `service/workflow/impl/WorkflowExecutorImpl.java` | 工作流执行编排 |
| 事件监听器 | `service/workflow/listener/TaskCompletionEventListener.java` | 任务完成事件处理 |
| 调度器 | `service/scheduler/impl/FifoSchedulerServiceImpl.java` | 资源管理和调度 |
| 转换工具 | `model/entity/WorkflowTaskInstance.toTaskInstance()` | 任务实例转换 |

### 🚀 最佳实践

1. **DAG引擎**：只创建PENDING任务，通过TaskSubmitService提交
2. **调度器**：从队列取任务，做资源决策，更新状态为RUNNING
3. **执行器**：执行任务，回调完成状态
4. **事件监听器**：监听完成事件，触发下一批任务

这样，系统的职责边界完全清晰，每层专注于自己的核心功能！🎉
