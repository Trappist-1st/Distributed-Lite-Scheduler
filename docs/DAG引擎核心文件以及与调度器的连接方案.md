基于对项目结构的分析，我为你详细规划 DAG 引擎的核心文件和与调度器的协调方案。

## 📌 DAG 引擎核心文件清单

### **第一层：数据模型与数据访问**

```
d:\A\1 IDEAprojects_rep\Distributed Lite Scheduler_V1\src\main\java\com\imperium\distributed_lite_scheduler_v1\
├── model/entity/
│   ├── Workflow.java                    ⭐ 工作流定义表实体
│   ├── WorkflowInstance.java            ⭐ 工作流执行实例实体
│   └── TaskInstance.java                ⭐ 任务实例实体（扩展支持关联WorkflowInstanceId）
│
├── model/dto/workflow/
│   ├── WorkflowDAG.java                 ⭐ DAG结构定义
│   ├── WorkflowTask.java                ⭐ DAG节点定义
│   ├── WorkflowDependency.java          ⭐ DAG边定义
│   ├── WorkflowCreateRequest.java
│   ├── WorkflowUpdateRequest.java
│   ├── WorkflowVO.java
│   └── RetryPolicy.java
│
└── mapper/
    ├── WorkflowMapper.java              ⭐ Workflow持久层
    └── (需新增) WorkflowInstanceMapper.java  ⭐ WorkflowInstance持久层
```

### **第二层：DAG引擎核心逻辑**

```
service/workflow/
├── WorkflowService.java                 ⭐ 工作流服务接口
│   ├── createWorkflow()         
│   ├── updateWorkflow()
│   ├── deleteWorkflow()
│   └── validateDAG()                    ⭐⭐ DAG循环依赖检测
│
├── impl/
│   ├── WorkflowServiceImpl.java          ⭐ 工作流CRUD实现
│   └── (需新增) DAGExecutionEngine.java ⭐⭐ DAG执行引擎核心
│       ├── submitWorkflow()             开始工作流执行
│       ├── calculateReadyTasks()        计算就绪任务
│       ├── triggerTaskInstances()       触发任务提交
│       ├── handleTaskCompletion()       处理任务完成事件
│       ├── evaluateCondition()          条件表达式求值
│       └── markWorkflowComplete()       标记工作流完成
│
└── (需新增) DAGValidator.java          ⭐⭐ DAG验证工具类
    ├── validateCyclicDependency()      检测循环依赖
    ├── validateNodeReferences()        验证节点引用完整性
    └── validateTaskIdExistence()       验证Task实体存在性
```

### **第三层：工作流调度与事件处理**

```
service/workflow/impl/
├── (需新增) WorkflowScheduler.java     ⭐⭐ 工作流调度器
│   ├── scheduleLoop()                  定时扫描待调度工作流
│   ├── triggerWorkflow()               手动触发工作流
│   └── processCronSchedules()          处理Cron调度
│
└── (需新增) WorkflowEventBus.java      ⭐⭐ 工作流事件总线
    ├── publishTaskCompleted()          发布任务完成事件
    ├── publishWorkflowCompleted()      发布工作流完成事件
    └── subscribeTaskCompletion()       订阅任务完成事件
```

---

## 🏗️ DAG 引擎执行流程

```
┌─────────────────────────────────────────────────────────────┐
│                    工作流完整生命周期                        │
└─────────────────────────────────────────────────────────────┘

1️⃣ 工作流创建阶段
   ┌──────────────────────────────┐
   │ 1. 解析DAG JSON              │
   │ 2. 验证循环依赖              │
   │ 3. 验证节点和Task引用        │
   │ 4. 保存Workflow实体          │
   └──────────────┬───────────────┘

2️⃣ 工作流提交阶段
   ┌──────────────────────────────┐
   │ 1. 创建WorkflowInstance      │
   │ 2. 标记状态为RUNNING         │
   │ 3. 发布工作流启动事件        │
   └──────────────┬───────────────┘

3️⃣ DAG执行阶段（核心）
   ┌──────────────────────────────────────────────────┐
   │  当前状态：计算就绪任务                           │
   │  ┌────────────────────────────────────────┐      │
   │  │ 规则：所有上游任务都已成功完成         │      │
   │  │ 且条件表达式（if exists）为真          │      │
   │  └────────────────────────────────────────┘      │
   │                                                   │
   │  第一批：所有入度为0的任务                        │
   │  └─→ 批量提交到调度器                            │
   │                                                   │
   │  后续批：监听任务完成事件                        │
   │  ├─ 任务SUCCESS  → 检查下游任务就绪              │
   │  ├─ 任务FAILED   → 判断是否需要重试/跳过         │
   │  └─ 任务ALL_RETRIES_FAILED → 工作流FAILED       │
   └──────────────────────────────────────────────────┘

4️⃣ 工作流完成阶段
   ┌──────────────────────────────────────┐
   │ 1. 检查所有任务状态                  │
   │ 2. 更新WorkflowInstance为SUCCESS/FAILED│
   │ 3. 发布工作流完成事件                │
   │ 4. 触发告警（if configured）        │
   └──────────────────────────────────────┘
```

---

## 🔗 DAG引擎与调度器的协调架构

### **核心思想：发布-订阅 + 队列解耦**

```
┌─────────────────────────────────────────────────────────────┐
│              工作流引擎 ←→ 调度器 交互架构                    │
└─────────────────────────────────────────────────────────────┘

DAG引擎侧                           调度器侧
│                                   │
├─ 计算就绪任务 ──┐                │
│                 │                │
│            ┌────▼─────────────┐  │
│            │  TaskSubmitQueue  │  │
│            │  (Redis List)     │  │
│            └─────┬────────────┘  │
│                  │                │ ← FIFO调度器
│                  │                │ ← 优先级调度器
│                  │                │ ← 资源感知调度器
│                  ├─→ pickTask()
│                  ├─→ checkQuota()
│                  ├─→ selectNode()
│                  └─→ createTaskInstance()
│                        ↓
│                    Redis Stream
│                    task-completion
│                   (Consumer Group)
│                        ↑
│            ┌──────────────────────┐
│            │ TaskCompletionEvent  │
│            │ - taskId             │
│            │ - status             │
│            │ - exitCode           │
│            │ - output             │
│            └──────────────────────┘
│                     ↑
└─ handleTaskCompletion()
   ├─ 更新TaskInstance
   ├─ 计算下游就绪任务
   └─ 提交下一批任务
```

### **具体协调流程**

#### **场景1：工作流启动**

```
1. 调用 DAGExecutionEngine.submitWorkflow(workflowId)
   
   ① 创建 WorkflowInstance
      status = "RUNNING"
   
   ② 计算入度为0的任务（第一批可执行任务）
      List<TaskInstance> readyTasks = calculateReadyTasks(workflowId)
   
   ③ 为每个就绪任务创建 TaskInstance
      TaskInstance ti = new TaskInstance()
      ti.setWorkflowInstanceId(workflowInstanceId)
      ti.setStatus(PENDING)
      ti.setTaskId(...)
      ti.setPriority(...)
   
   ④ 批量提交到调度器队列
      taskSubmitService.batchSubmit(taskInstances)
      
         内部逻辑：
         for (TaskInstance ti : taskInstances) {
             // 加入削峰队列
             submitQueue.offer(ti)
         }
   
   ⑤ 监听这些任务的完成事件
      - 订阅 task:completed:{taskInstanceId}
```

#### **场景2：调度器执行任务**

```
1. 调度器主循环（FifoSchedulerService.scheduleLoop()）
   
   ① 从删峰队列取出TaskInstance
      TaskInstance ti = submitQueue.poll()
   
   ② 执行标准调度流程
      - 检查配额
      - 选择资源节点
      - 预留资源
      - 更新状态为 RUNNING
      - 提交执行器
   
   ③ 将 TaskInstance 提交到 Worker 执行
      executor.submit(ti)
```

#### **场景3：任务完成事件处理（关键）**

```
1. Worker 执行完任务，回调 TaskInstanceController
   
   PUT /api/task-instance/{id}/status
   {
     "status": "SUCCESS",
     "exitCode": 0,
     "output": { ... }
   }

2. TaskInstanceService 更新 DB
   ti.setStatus("SUCCESS")
   ti.setEndTime(now)
   taskInstanceMapper.updateById(ti)

3. 发布事件（Redis Stream）
   String streamKey = "task-completion"
   Map<String, String> eventData = new HashMap<>()
   eventData.put("taskInstanceId", ti.getId().toString())
   eventData.put("status", event.status)
   eventData.put("exitCode", event.exitCode)
   eventData.put("output", JSON.toJsonString(event.output))
   redisTemplate.opsForStream().add(streamKey, eventData)

4. DAGExecutionEngine 通过消费者组监听处理
   StreamListener 订阅 "task-completion" Stream
   消费者组："dag-engine-group"
   public void handleTaskCompletion(TaskCompletionEvent event) {
       
       ① 更新WorkflowInstance中的统计
          if (event.status == "SUCCESS") {
              workflowInstance.setSuccessTasks(+1)
          }
       
       ② 查询是否所有任务完成
          boolean allDone = checkIfAllTasksComplete(workflowInstanceId)
          if (allDone) {
              markWorkflowComplete(workflowInstanceId)
              return
          }
       
       ③ 计算下一批就绪任务
          List<Long> readyTaskIds = calculateReadyTasks(workflowInstanceId)
          
          逻辑：
          for (Task task : DAG.tasks) {
              if (task.status == PENDING) {
                  // 检查所有上游是否完成
                  boolean upstreamDone = checkUpstreamCompletion(task)
                  if (!upstreamDone) continue
                  
                  // 计算依赖条件
                  for (Dependency dep : getDependenciesTo(task)) {
                      boolean conditionMet = evaluateCondition(
                          dep.condition, 
                          executionContext
                      )
                      if (!conditionMet) {
                          task.skip = true
                          break
                      }
                  }
                  
                  if (!task.skip) {
                      readyTaskIds.add(task.id)
                  }
              }
          }
       
       ④ 为就绪任务创建新的 TaskInstance
          for (Long taskId : readyTaskIds) {
              TaskInstance newTi = new TaskInstance()
              newTi.setWorkflowInstanceId(workflowInstanceId)
              newTi.setTaskId(taskId)
              newTi.setStatus(PENDING)
              newTi.setPriority(calculatePriority(...))
              taskInstanceMapper.insert(newTi)
          }
       
       ⑤ 批量提交到调度器
          taskSubmitService.batchSubmit(newTaskInstances)
   }
```

---

## 📋 文件实现规划

### **阶段1：基础框架（优先实现）**


| 文件                            | 作用                            | 优先级 |
| ----------------------------- | ----------------------------- | --- |
| `DAGValidator.java`           | DAG验证（循环检测、节点引用）              | ⭐⭐⭐ |
| `DAGExecutionEngine.java`     | DAG执行引擎核心                     | ⭐⭐⭐ |
| WorkflowServiceImpl.java      | 补全 validateDAG/createWorkflow | ⭐⭐⭐ |
| `WorkflowInstanceMapper.java` | WorkflowInstance 持久层          | ⭐⭐  |
| `WorkflowScheduler.java`      | 工作流调度触发                       | ⭐⭐  |


### **阶段2：事件驱动（后续实现）**


| 文件                                | 作用                 | 优先级 |
| --------------------------------- | ------------------ | --- |
| `WorkflowEventBus.java`           | Redis Stream 事件总线（消费者组） | ⭐⭐  |
| `TaskCompletionListener.java`     | 任务完成事件监听器          | ⭐⭐  |
| `WorkflowConditionEvaluator.java` | 条件表达式求值（SpEL）      | ⭐   |


### **阶段3：集成优化（最后优化）**


| 文件                                      | 作用                       | 优先级 |
| --------------------------------------- | ------------------------ | --- |
| 增强 TaskInstanceService.java             | 支持 workflowInstanceId 字段 | ⭐⭐  |
| 增强 `ResourceAwareSchedulerService.java` | 支持工作流任务优先级               | ⭐   |
| 增强 TaskSubmitService.java               | 支持批量提交时保序                | ⭐   |


---

## 🎯 与调度器的连接点总结


| 连接点      | DAG引擎职责               | 调度器职责          | 交互方式                            |
| -------- | --------------------- | -------------- | ------------------------------- |
| **任务提交** | 计算就绪任务 → TaskInstance | 检查配额 → 选点 → 执行 | TaskSubmitQueue                 |
| **任务完成** | 监听事件 → 计算下游 → 提交下一批   | 执行任务 → 回调状态    | Redis Stream（消费者组）        |
| **优先级**  | 工作流级优先级               | 任务级优先级综合       | TaskInstance.priority           |
| **资源约束** | DAG层面超时控制             | 任务层面资源分配       | WorkflowInstance.timeoutSeconds |
| **失败处理** | 判断是否重试/跳过/失败          | 单任务重试          | RetryPolicy in Task             |
| **并行执行** | 同一批就绪任务并行             | 各调度器独立调度       | 异步处理                            |


---

## 💡 关键设计建议

### **1. 使用Redis Stream而不是Pub/Sub或直接方法调用**

```
Redis Stream 相比 Pub/Sub 的优势：
  • 消息持久化 - 支持历史消息回放
  • 消费者组 - 支持分布式消费和消息确认
  • 消息追溯 - 便于调试和问题诊断
  • 自动重连 - 消费者离线期间不丢失消息

使用消费者组管理任务完成事件：
  • Stream Key: "task-completion"
  • Consumer Group: "dag-engine-group"
  • 多个DAG引擎实例可并行消费，无单点压力
  • 自动确认机制保证消息至少被处理一次

关键状态变更用DB + Stream事件双写保证一致性
```

### **2. WorkflowInstance 中应保存DAG快照**

```
不要只存 workflowId，因为 Workflow 可能被修改
保存解析后的 DAG 结构快照，便于追溯历史执行
```

### **3. 条件表达式使用 SpEL**

```
Spring Expression Language
${tasks.validate_data.exitCode == 0}
${tasks.extract.output.score >= 90}
便于动态计算，无需代码改动
```

### **4. 批量优化：一批就绪任务集中提交**

```
不要逐个提交（N个数据库写入）
改为批量提交：N条 INSERT ... UNION ALL
减少数据库往返，提升吞吐
```

### **5. 重试策略继承与覆盖**

```
优先级：TaskInstance.retryOverride > WorkflowTask.retryOverride > Task.retryPolicy
灵活支持不同层级的定制化
```

这个架构既能充分利用现有调度器的能力，又提供了DAG编排的灵活性。