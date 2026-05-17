# WorkflowTask 重构总结

## 重构时间

2026-05-04

## 重构原因

原设计中 `WorkflowTask` 与 `Task` 实体存在大量字段重复，导致：

1. **数据冗余**：任务类型、执行命令、资源需求、重试策略等配置重复定义
2. **不可复用**：无法复用已定义的 Task 实体，每个工作流都需要重新配置任务
3. **一致性风险**：修改 Task 定义时，已有工作流中的 WorkflowTask 不会自动更新
4. **维护成本高**：同一个任务在多个工作流中使用时，需要多处维护

## 修改的文件

### 1. WorkflowTask.java（核心修改）

**修改前**：包含完整的任务配置（type、command、script、image、resourceRequirement 等）

**修改后**：引用 Task 实体，支持参数覆盖

| 字段 | 类型 | 说明 |
|------|------|------|
| nodeName | String | 节点在DAG中的唯一标识（原 name） |
| taskId | Long | 引用的任务定义ID（新增） |
| displayName | String | 节点显示名称（保留） |
| paramOverrides | Map<String, Object> | 参数覆盖（原 params） |
| resourceOverride | ResourceRequirement | 资源需求覆盖（原 resourceRequirement） |
| retryOverride | RetryPolicy | 重试策略覆盖（原 retryPolicy） |
| timeoutOverride | Integer | 超时时间覆盖（新增） |

**移除的字段**：
- ~~type~~（使用 Task.taskType）
- ~~command~~（使用 Task.executorConfig）
- ~~script~~（使用 Task.executorConfig）
- ~~image~~（使用 Task.executorConfig）

### 2. WorkflowDependency.java

更新注释，明确 from/to 使用的是 `nodeName` 而非 `taskId`。

### 3. WorkflowDAG.java

更新注释，说明 DAG 的验证规则和执行规则。

### 4. RetryPolicy.java

优化字段命名和注释，使其与 Task 实体字段对应：

| 字段 | 类型 | 对应 Task 字段 | 说明 |
|------|------|----------------|------|
| maxRetries | Integer | Task.retryTimes | 最大重试次数 |
| retryInterval | Integer | Task.retryInterval | 重试间隔（秒） |
| backoffMultiplier | Double | - | 退避倍数（可选） |
| maxRetryInterval | Integer | - | 最大重试间隔（可选） |

### 5. WorkflowService.java

更新 `validateDAG()` 方法注释，增加对 `taskId` 引用验证的说明。

### 6. WorkflowServiceImpl.java

更新验证逻辑注释：
- 节点名称唯一性验证
- 引用的 Task 实体存在性验证
- 依赖关系节点存在性验证
- 循环依赖检测

## 新的设计架构

### 数据模型关系

```
┌─────────────────┐
│   Workflow      │ 工作流定义
│─────────────────│
│ id              │
│ workflow_name   │
│ dag_json        │ ──> 存储 WorkflowDAG（JSON）
│ schedule_type   │
│ cron_expression │
└─────────────────┘

        │ contains
        ▼

┌─────────────────┐
│ WorkflowDAG     │ DAG定义（JSON结构）
│─────────────────│
│ version         │
│ tasks           │ ──> List<WorkflowTask>
│ dependencies    │ ──> List<WorkflowDependency>
└─────────────────┘

        │ contains
        ▼

┌─────────────────┐           ┌─────────────────┐
│ WorkflowTask    │           │ WorkflowDependency│
│─────────────────│           │─────────────────│
│ nodeName        │<──────────│ from            │
│ taskId          │───┐       │ to              │
│ paramOverrides  │   │       │ condition       │
│ resourceOverride│   │       └─────────────────┘
│ retryOverride   │   │
│ timeoutOverride │   │
└─────────────────┘   │ references
                      │
                      ▼
              ┌─────────────────┐
              │      Task       │ 任务定义（可复用）
              │─────────────────│
              │ id              │
              │ task_name       │
              │ task_type       │
              │ executor_config │
              │ timeout_seconds │
              │ retry_times     │
              │ retry_interval  │
              │ resource_...    │
              └─────────────────┘

                      │ creates instances
                      ▼

              ┌─────────────────┐
              │  TaskInstance   │ 任务实例
              │─────────────────│
              │ id              │
              │ task_id         │────────┘
              │ workflow_...    │
              │ status          │
              │ start_time      │
              │ end_time        │
              └─────────────────┘
```

### 职责分离

| 层次 | 组件 | 职责 |
|------|------|------|
| 定义层 | Task | 定义可复用的任务配置（类型、执行器、资源等） |
| 编排层 | WorkflowTask | 在DAG中引用Task，提供工作流级别的参数覆盖 |
| 编排层 | WorkflowDependency | 定义任务节点之间的依赖关系 |
| 编排层 | WorkflowDAG | 组织完整的工作流执行图 |
| 编排层 | Workflow | 工作流元数据和调度配置 |
| 执行层 | TaskInstance | 任务的实际执行实例 |

## 核心设计理念

### 1. 复用性（Reusability）

一个 Task 可以被多个 Workflow 复用：

```
Task: data_validation (id=101)
  ├─ Workflow A (nodeName: validate_input)
  ├─ Workflow B (nodeName: check_data)
  ├─ Workflow C (nodeName: validate_raw_data)
  └─ Workflow C (nodeName: validate_processed_data)  // 同一工作流多次使用
```

### 2. 一致性（Consistency）

修改 Task 定义，所有引用它的工作流自动生效：

```sql
-- 修改 Task 的超时时间
UPDATE task SET timeout_seconds = 7200 WHERE id = 101;

-- 所有引用 taskId=101 的工作流节点都会使用新的超时时间
-- （除非 WorkflowTask 设置了 timeoutOverride）
```

### 3. 灵活性（Flexibility）

通过覆盖参数支持工作流级别的定制：

```json
{
  "nodeName": "validate_special",
  "taskId": 101,
  "paramOverrides": {
    "validation_level": "strict",
    "custom_rule": "business_specific"
  },
  "resourceOverride": {
    "cpuCores": 8,
    "memoryMb": 16384
  },
  "timeoutOverride": 7200
}
```

### 4. 清晰性（Clarity）

职责明确，Task 定义与工作流编排解耦：

- **Task**：定义"做什么"（任务类型、执行命令、默认配置）
- **WorkflowTask**：定义"在哪做"（DAG中的位置）和"怎么做"（参数覆盖）
- **WorkflowDependency**：定义"按什么顺序做"（依赖关系）

## 参数合并策略

执行 WorkflowTask 时，参数合并优先级（从低到高）：

1. **Task.executorConfig**（任务默认配置）
2. **WorkflowTask.paramOverrides**（工作流级别覆盖）
3. **运行时参数**（触发时传入）

示例：

```java
// Task 默认配置
Task.executorConfig = {
  "command": "python validate.py",
  "timeout": 3600,
  "log_level": "INFO"
}

// WorkflowTask 覆盖
WorkflowTask.paramOverrides = {
  "timeout": 7200,
  "input_path": "/data/batch_001"
}

// 运行时参数
RuntimeParams = {
  "input_path": "/data/batch_002",
  "debug": true
}

// 最终执行参数
FinalParams = {
  "command": "python validate.py",      // 来自 Task
  "timeout": 7200,                      // 来自 WorkflowTask
  "log_level": "INFO",                  // 来自 Task
  "input_path": "/data/batch_002",      // 来自 RuntimeParams（最高优先级）
  "debug": true                          // 来自 RuntimeParams
}
```

## DAG 验证规则

实现 `WorkflowService.validateDAG()` 时需要验证：

### 1. 节点名称唯一性

```java
Set<String> nodeNames = new HashSet<>();
for (WorkflowTask task : dag.getTasks()) {
    if (!nodeNames.add(task.getNodeName())) {
        throw new IllegalArgumentException("节点名称重复: " + task.getNodeName());
    }
}
```

### 2. 引用的 Task 存在

```java
Set<Long> taskIds = dag.getTasks().stream()
    .map(WorkflowTask::getTaskId)
    .collect(Collectors.toSet());
List<Task> tasks = taskMapper.selectBatchIds(taskIds);
if (tasks.size() != taskIds.size()) {
    throw new IllegalArgumentException("引用的任务不存在");
}
```

### 3. 依赖引用的节点存在

```java
Set<String> nodeNameSet = dag.getTasks().stream()
    .map(WorkflowTask::getNodeName)
    .collect(Collectors.toSet());
for (WorkflowDependency dep : dag.getDependencies()) {
    if (!nodeNameSet.contains(dep.getFrom())) {
        throw new IllegalArgumentException("上游节点不存在: " + dep.getFrom());
    }
    if (!nodeNameSet.contains(dep.getTo())) {
        throw new IllegalArgumentException("下游节点不存在: " + dep.getTo());
    }
}
```

### 4. 无循环依赖

使用 DFS + 三色标记算法检测环。

## 使用示例对比

### 旧设计（重复定义）

```json
{
  "tasks": [
    {
      "name": "validate_data_a",
      "type": "shell",
      "command": "python validate.py --input /data/a",
      "resourceRequirement": {"cpuCores": 2, "memoryMb": 4096},
      "retryPolicy": {"maxAttempts": 3, "baseDelaySeconds": 60}
    },
    {
      "name": "validate_data_b",
      "type": "shell",
      "command": "python validate.py --input /data/b",
      "resourceRequirement": {"cpuCores": 2, "memoryMb": 4096},
      "retryPolicy": {"maxAttempts": 3, "baseDelaySeconds": 60}
    }
  ]
}
```

**问题**：
- 配置重复，难以维护
- 修改验证逻辑需要更新两处
- 无法复用已定义的任务

### 新设计（引用复用）

```json
{
  "tasks": [
    {
      "nodeName": "validate_data_a",
      "taskId": 101,
      "paramOverrides": {"input_path": "/data/a"}
    },
    {
      "nodeName": "validate_data_b",
      "taskId": 101,
      "paramOverrides": {"input_path": "/data/b"}
    }
  ]
}
```

**优势**：
- 配置简洁，只需指定差异部分
- 修改 Task 101，两个节点自动更新
- 完全复用已定义的任务

## 迁移指南

### 如果已有旧版 WorkflowTask 数据

1. **提取 Task 定义**

```sql
-- 从现有 WorkflowTask 提取 Task 定义
INSERT INTO task (task_name, task_code, task_type, executor_config, ...)
SELECT DISTINCT 
    name,
    name,
    type,
    JSON_OBJECT('command', command, 'script', script, 'image', image),
    ...
FROM old_workflow_tasks;
```

2. **更新 WorkflowTask 引用**

```java
// 旧代码
WorkflowTask oldTask = new WorkflowTask();
oldTask.setName("validate_data");
oldTask.setType("shell");
oldTask.setCommand("python validate.py");
oldTask.setResourceRequirement(...);

// 新代码
WorkflowTask newTask = new WorkflowTask();
newTask.setNodeName("validate_data");
newTask.setTaskId(101L);  // 引用已创建的 Task
newTask.setParamOverrides(Map.of("input", "/data/input"));
```

## 向后兼容性

### 不兼容变更

1. **WorkflowTask 字段变更**
   - `name` 改为 `nodeName`
   - 移除 `type`、`command`、`script`、`image`
   - 新增 `taskId`（必填）

2. **API 变更**
   - 创建工作流时需要先创建 Task
   - DAG JSON 结构变化

### 迁移建议

1. 在新版本中提供迁移工具，自动将旧版 WorkflowTask 转换为新版
2. 保留旧版 API 一段时间，标记为 `@Deprecated`
3. 提供详细的迁移文档和示例

## 后续扩展方向

基于这个设计，可以轻松实现：

1. **任务版本管理**：Task 表增加 version 字段，WorkflowTask 引用特定版本
2. **跨项目任务共享**：Task 标记为 `shared=true`，可被其他项目使用
3. **任务市场**：提供公共任务库，用户直接引用
4. **任务模板**：预定义常用任务组合，快速创建工作流
5. **动态参数校验**：根据 Task 定义的参数 schema，校验 paramOverrides
6. **任务权限控制**：控制哪些用户可以使用哪些 Task

## 文档清单

本次重构创建了以下文档：

1. **WORKFLOW_TASK_REFACTORING.md**：详细的重构说明和设计理念
2. **WORKFLOW_API_EXAMPLES.md**：完整的 API 使用示例
3. **WORKFLOW_REFACTORING_SUMMARY.md**（本文档）：重构总结

## 总结

这次重构通过引入 **Task 引用机制**，实现了：

- ✅ **减少冗余**：任务配置只需定义一次
- ✅ **提高复用性**：一个 Task 可被多个工作流使用
- ✅ **保证一致性**：Task 修改自动生效到所有工作流
- ✅ **增强灵活性**：支持工作流级别的参数覆盖
- ✅ **清晰的职责**：Task 定义与工作流编排解耦
- ✅ **易于维护**：集中管理任务定义，减少维护成本
- ✅ **便于扩展**：为任务版本管理、跨项目共享等奠定基础

这种设计更符合分布式调度系统的最佳实践，也是主流工作流引擎（如 Airflow、Argo Workflows）采用的设计模式。
