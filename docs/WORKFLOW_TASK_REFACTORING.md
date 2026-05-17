# WorkflowTask 重构说明

## 重构背景

原设计中 `WorkflowTask` 与 `Task` 实体存在大量字段重复，导致：
1. **数据冗余**：同样的任务配置要维护两份
2. **不可复用**：无法复用已定义的Task实体
3. **一致性风险**：修改Task时，WorkflowTask不会自动更新

## 新设计架构

### 核心理念

**WorkflowTask 不再重复定义任务配置，而是引用已存在的 Task 实体**

```
Workflow（工作流定义）
  └─ WorkflowDAG（DAG定义）
       ├─ tasks: List<WorkflowTask>（节点列表）
       └─ dependencies: List<WorkflowDependency>（边列表）

WorkflowTask（DAG节点）
  ├─ nodeName: String（节点名称，在DAG中唯一）
  ├─ taskId: Long（引用 Task 实体）
  ├─ paramOverrides: Map（参数覆盖）
  ├─ resourceOverride: ResourceRequirement（资源覆盖）
  ├─ retryOverride: RetryPolicy（重试策略覆盖）
  └─ timeoutOverride: Integer（超时覆盖）

Task（任务定义）← 可被多个工作流复用
  ├─ id, taskName, taskCode
  ├─ taskType（SHELL/PYTHON/DOCKER/K8S_JOB）
  ├─ executorConfig（执行器配置）
  ├─ resourceRequirement（资源需求）
  ├─ retryTimes, retryInterval（重试配置）
  └─ timeoutSeconds（超时时间）

TaskInstance（任务执行实例）
  ├─ taskId → Task
  └─ workflowInstanceId（可选，工作流实例ID）
```

### 关系说明

1. **WorkflowTask.nodeName**：节点在DAG中的唯一标识，用于依赖引用
2. **WorkflowTask.taskId**：引用 `task` 表中已定义的任务
3. **WorkflowDependency.from/to**：使用 `nodeName` 建立依赖关系
4. **Task**：任务的完整定义，可被多个工作流复用
5. **TaskInstance**：任务的执行实例，通过 `workflowInstanceId` 关联到工作流实例

## 使用示例

### 1. 创建可复用的任务定义

首先在 `task` 表中定义可复用的任务：

```sql
-- 数据验证任务
INSERT INTO task (task_name, task_code, task_type, executor_config, timeout_seconds, retry_times, retry_interval)
VALUES ('数据验证', 'data_validation', 'SHELL', '{"command": "python scripts/validate.py"}', 3600, 3, 60);

-- 特征提取任务
INSERT INTO task (task_name, task_code, task_type, executor_config, timeout_seconds, retry_times, retry_interval)
VALUES ('特征提取', 'feature_extraction', 'PYTHON', '{"script": "scripts/extract_features.py"}', 7200, 2, 120);

-- 模型训练任务
INSERT INTO task (task_name, task_code, task_type, executor_config, timeout_seconds, retry_times, retry_interval)
VALUES ('模型训练', 'model_training', 'DOCKER', '{"image": "ml-train:latest"}', 14400, 1, 0);
```

### 2. 创建工作流时引用这些任务

```json
{
  "projectId": 1,
  "workflowName": "机器学习训练流水线",
  "workflowCode": "ml_training_pipeline",
  "description": "数据验证 → 特征提取 → 模型训练",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "validate_data",
        "taskId": 101,
        "displayName": "数据验证步骤",
        "paramOverrides": {
          "input_path": "/data/raw/batch_001",
          "validation_rules": "strict"
        }
      },
      {
        "nodeName": "extract_features",
        "taskId": 102,
        "displayName": "特征提取步骤",
        "paramOverrides": {
          "feature_config": "config/features_v2.json"
        },
        "resourceOverride": {
          "cpuCores": 8,
          "memoryMb": 16384
        }
      },
      {
        "nodeName": "train_model",
        "taskId": 103,
        "displayName": "模型训练步骤",
        "paramOverrides": {
          "model_type": "xgboost",
          "epochs": 100
        },
        "timeoutOverride": 28800
      }
    ],
    "dependencies": [
      {
        "from": "validate_data",
        "to": "extract_features",
        "condition": "${tasks.validate_data.exitCode == 0}"
      },
      {
        "from": "extract_features",
        "to": "train_model",
        "condition": "${tasks.extract_features.status == 'SUCCESS'}"
      }
    ]
  },
  "scheduleType": "CRON",
  "cronExpression": "0 2 * * *",
  "timeoutSeconds": 43200
}
```

### 3. 任务复用示例

同一个 Task 可以在不同工作流中复用，通过不同的 nodeName 和参数覆盖实现定制化：

```json
{
  "workflowName": "A/B测试流水线",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "validate_data_a",
        "taskId": 101,
        "paramOverrides": {
          "input_path": "/data/experiment_a"
        }
      },
      {
        "nodeName": "validate_data_b",
        "taskId": 101,
        "paramOverrides": {
          "input_path": "/data/experiment_b"
        }
      },
      {
        "nodeName": "train_model_a",
        "taskId": 103,
        "paramOverrides": {
          "model_type": "random_forest"
        }
      },
      {
        "nodeName": "train_model_b",
        "taskId": 103,
        "paramOverrides": {
          "model_type": "xgboost"
        }
      }
    ],
    "dependencies": [
      {"from": "validate_data_a", "to": "train_model_a"},
      {"from": "validate_data_b", "to": "train_model_b"}
    ]
  }
}
```

在这个例子中：
- `taskId=101`（数据验证）被复用了2次，分别验证A/B两组数据
- `taskId=103`（模型训练）被复用了2次，分别训练两个不同算法的模型

## 设计优势

### 1. 复用性

一个 Task 可以被多个 Workflow 使用，避免重复定义：

```
Task: data_validation (id=101)
  ├─ 被 Workflow A 使用（nodeName: validate_input）
  ├─ 被 Workflow B 使用（nodeName: check_data）
  └─ 被 Workflow C 使用（nodeName: validate_data）
```

### 2. 一致性

修改 Task 定义，所有使用它的工作流自动生效：

```java
// 修改 Task 的超时时间
Task task = taskMapper.selectById(101L);
task.setTimeoutSeconds(7200);
taskMapper.updateById(task);

// 所有引用 taskId=101 的工作流节点都会使用新的超时时间
// （除非工作流节点设置了 timeoutOverride）
```

### 3. 灵活性

通过覆盖参数支持工作流级别的定制化：

```java
WorkflowTask task = new WorkflowTask();
task.setNodeName("validate_special");
task.setTaskId(101L);  // 引用通用的验证任务

// 在工作流级别覆盖参数
task.setParamOverrides(Map.of(
    "validation_level", "strict",
    "custom_rule", "business_specific_rule"
));

// 在工作流级别覆盖资源需求
task.setResourceOverride(new ResourceRequirement(16, 32768));
```

### 4. 清晰的职责分离

```
Task（任务定义层）
  - 定义任务的通用配置
  - 定义任务的默认行为
  - 可以独立管理和维护

WorkflowTask（工作流编排层）
  - 定义任务在DAG中的位置
  - 定义任务间的依赖关系
  - 覆盖特定工作流的参数
```

## 数据库设计

### workflow 表（不变）

```sql
CREATE TABLE workflow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    workflow_name VARCHAR(100) NOT NULL,
    workflow_code VARCHAR(50) UNIQUE,
    description TEXT,
    dag_json JSON NOT NULL,  -- 存储 WorkflowDAG
    schedule_type VARCHAR(20),
    cron_expression VARCHAR(100),
    timeout_seconds INT,
    alert_on_failure TINYINT,
    creator_user_id BIGINT,
    status TINYINT DEFAULT 1,
    version INT DEFAULT 1,
    created_at DATETIME,
    updated_at DATETIME,
    deleted TINYINT DEFAULT 0
);
```

### task 表（已存在）

```sql
CREATE TABLE task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_name VARCHAR(100) NOT NULL,
    task_code VARCHAR(50) UNIQUE,
    task_type VARCHAR(20),
    executor_config JSON,
    schedule_type VARCHAR(20),
    cron_expression VARCHAR(100),
    timeout_seconds INT,
    retry_times INT,
    retry_interval INT,
    priority INT,
    resource_requirement JSON,
    alert_on_failure TINYINT,
    alert_on_timeout TINYINT,
    description TEXT,
    creator_user_id BIGINT,
    status TINYINT DEFAULT 1,
    version INT DEFAULT 1,
    created_at DATETIME,
    updated_at DATETIME,
    deleted TINYINT DEFAULT 0
);
```

### dag_json 结构示例

```json
{
  "version": "1.0",
  "tasks": [
    {
      "nodeName": "validate_data",
      "taskId": 101,
      "displayName": "数据验证",
      "paramOverrides": {
        "input_path": "/data/input"
      },
      "resourceOverride": {
        "cpuCores": 4,
        "memoryMb": 8192
      },
      "retryOverride": {
        "maxRetries": 5,
        "retryInterval": 60,
        "backoffMultiplier": 2.0
      },
      "timeoutOverride": 3600
    }
  ],
  "dependencies": [
    {
      "from": "validate_data",
      "to": "extract_features",
      "condition": "${tasks.validate_data.exitCode == 0}"
    }
  ]
}
```

## DAG 验证规则

实现 `WorkflowService.validateDAG()` 时需要验证：

1. **节点名称唯一性**
   ```java
   Set<String> nodeNames = new HashSet<>();
   for (WorkflowTask task : dag.getTasks()) {
       if (!nodeNames.add(task.getNodeName())) {
           throw new IllegalArgumentException("节点名称重复: " + task.getNodeName());
       }
   }
   ```

2. **引用的 Task 存在**
   ```java
   Set<Long> taskIds = dag.getTasks().stream()
       .map(WorkflowTask::getTaskId)
       .collect(Collectors.toSet());
   List<Task> tasks = taskMapper.selectBatchIds(taskIds);
   if (tasks.size() != taskIds.size()) {
       throw new IllegalArgumentException("引用的任务不存在");
   }
   ```

3. **依赖引用的节点存在**
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

4. **无循环依赖**（使用 DFS + 三色标记算法）

## 执行时参数合并策略

当执行 WorkflowTask 时，参数合并优先级（从低到高）：

1. **Task.executorConfig**（任务默认配置）
2. **WorkflowTask.paramOverrides**（工作流级别覆盖）
3. **运行时参数**（触发时传入）

```java
// 伪代码：执行任务时的参数合并
Map<String, Object> finalParams = new HashMap<>();

// 1. 加载 Task 的默认配置
Task task = taskMapper.selectById(workflowTask.getTaskId());
Map<String, Object> defaultConfig = parseExecutorConfig(task.getExecutorConfig());
finalParams.putAll(defaultConfig);

// 2. 应用工作流级别的覆盖
if (workflowTask.getParamOverrides() != null) {
    finalParams.putAll(workflowTask.getParamOverrides());
}

// 3. 应用运行时参数
if (runtimeParams != null) {
    finalParams.putAll(runtimeParams);
}

// 使用 finalParams 执行任务
executeTask(finalParams);
```

## 迁移指南

如果已有代码使用了旧版 WorkflowTask，需要进行以下迁移：

### 迁移步骤

1. **提取重复的任务定义到 Task 表**
   ```sql
   INSERT INTO task (task_name, task_code, task_type, executor_config, ...)
   SELECT DISTINCT name, name, type, JSON_OBJECT('command', command, ...), ...
   FROM (旧的 WorkflowTask 数据);
   ```

2. **更新 WorkflowTask 引用**
   ```java
   // 旧代码
   WorkflowTask oldTask = new WorkflowTask();
   oldTask.setName("validate_data");
   oldTask.setType("shell");
   oldTask.setCommand("python validate.py");
   
   // 新代码
   WorkflowTask newTask = new WorkflowTask();
   newTask.setNodeName("validate_data");
   newTask.setTaskId(101L);  // 引用已创建的 Task
   newTask.setParamOverrides(Map.of("input", "/data/input"));
   ```

3. **更新依赖引用**
   ```java
   // 依赖关系保持不变，但注意：
   // from/to 使用的是 WorkflowTask.nodeName
   WorkflowDependency dep = new WorkflowDependency();
   dep.setFrom("validate_data");  // nodeName，不是 taskId
   dep.setTo("extract_features");
   ```

## 总结

重构后的设计实现了：
- ✅ **复用性**：Task 可以被多个 Workflow 复用
- ✅ **一致性**：Task 修改自动生效到所有工作流
- ✅ **灵活性**：支持工作流级别的参数覆盖
- ✅ **清晰性**：职责分离，Task 定义与工作流编排解耦
- ✅ **可维护性**：减少重复代码，便于维护和扩展

这种设计更符合分布式调度系统的最佳实践，也更易于后续扩展（如任务版本管理、跨项目任务共享等）。
