# 任务提交字段重构说明

## 重构目标
简化字段设计，消除冗余，明确职责边界，让代码更清晰易维护。

---

## 1. TaskSubmitRequest 字段重构

### 重构前问题
- 包含大量后端自动生成的字段（`tenantId`、`submitUserId`、`taskInstanceId` 等）
- 用户需要传递 Task 定义字段（`taskName`、`taskType` 等），容易导致不一致
- 字段验证注解混乱（`@NotBlank`、`@NotNull` 使用不合理）

### 重构后设计

#### 用户提供字段（API 请求必须包含）
```java
@NotNull(message = "taskId不能为空")
private Long taskId;                    // 必填：任务定义ID

@Min(value = 1, message = "priority最小值为1")
@Max(value = 10, message = "priority最大值为10")
private Integer priority;               // 可选：优先级（1-10），默认使用Task定义的优先级

private Map<String, Object> parameters; // 可选：任务参数
```

#### 后端自动填充字段
```java
private Long tenantId;           // 从JWT令牌提取
private Long submitUserId;       // 从SecurityContext提取
private Long taskInstanceId;     // 雪花算法生成
private LocalDateTime submitTime; // 系统时间
private String traceId;          // 从MDC提取
```

#### 从Task定义快照的字段
```java
private String taskName;           // 从Task表查询
private String taskType;           // 从Task表查询
private String executorConfig;     // 从Task表查询（JSON）
private String resourceRequirement; // 从Task表查询（JSON）
```

### 字段填充时机
1. **API 接收阶段**：只接收 `taskId`、`priority`（可选）、`parameters`（可选）
2. **Service 处理阶段**：
   - 根据 `taskId` 查询 Task 定义
   - 填充快照字段（`taskName`、`taskType`、`executorConfig`、`resourceRequirement`）
   - 填充上下文字段（`tenantId`、`submitUserId`、`traceId`）
   - 生成唯一ID（`taskInstanceId`）
   - 记录提交时间（`submitTime`）

---

## 2. Task 实体字段统一

### 字段命名修正
```java
// 修改前
private String resourceRequire;

// 修改后
private String resourceRequirement;
```

### 优先级范围说明
- **范围**：1-10（数值越大优先级越高）
- **默认值**：5（中等优先级）
- **用途**：用于任务调度排序

---

## 3. TaskInstance 实体字段优化

### 移除冗余字段
```java
// 已移除：triggerUserId（与submitUserId重复）
```

### 保留核心字段
```java
private Long submitUserId;     // 提交用户ID（统一使用这个字段）
private String triggerType;    // 触发类型：MANUAL/CRON/DEPENDENCY/API
```

### 字段说明
- **submitUserId**：任务提交者的用户ID（适用于所有触发类型）
- **triggerType**：区分触发来源
  - `MANUAL`：手动触发
  - `CRON`：定时触发
  - `DEPENDENCY`：依赖触发
  - `API`：API提交触发（本次实现）

---

## 4. TaskSubmitServiceImpl 业务逻辑优化

### 主要改进

#### 1) Task 定义查询与验证
```java
Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
        .eq(Task::getId, taskId)
        .eq(Task::getStatus, 1));

if (task == null) {
    return Result.failure(404, "任务不存在或已被禁用");
}
```

#### 2) 字段自动填充
```java
// 优先级处理：用户未指定时使用Task定义的优先级，Task也未指定时使用默认值5
if (request.getPriority() == null) {
    request.setPriority(task.getPriority() != null ? task.getPriority() : DEFAULT_PRIORITY);
}

// 快照Task定义字段
request.setTaskName(task.getTaskName());
request.setTaskType(task.getTaskType());
request.setExecutorConfig(task.getExecutorConfig());
request.setResourceRequirement(task.getResourceRequirement());
```

#### 3) 参数序列化处理
```java
try {
    if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        taskInstance.setParameters(objectMapper.writeValueAsString(request.getParameters()));
    }
} catch (JsonProcessingException e) {
    log.error("参数序列化失败 taskInstanceId={}", request.getTaskInstanceId(), e);
    taskInstance.setParameters("{}");
}
```

#### 4) 批量消费实现
```java
@Scheduled(fixedDelay = 1000)
public void batchConsume() {
    List<TaskSubmitRequest> batch = new ArrayList<>(BATCH_SIZE);
    int drained = taskSubmitQueue.drainTo(batch, BATCH_SIZE);
    
    if (drained == 0) {
        return;
    }
    
    // 批量插入数据库
    // ...
}
```

---

## 5. 校验规则总结

### TaskSubmitRequest 校验
| 字段 | 校验规则 | 说明 |
|------|----------|------|
| taskId | @NotNull | 必填，任务定义ID |
| priority | @Min(1) @Max(10) | 可选，范围1-10 |
| parameters | 无 | 可选，任务参数Map |

### 业务逻辑校验
1. **Task 存在性校验**：检查 taskId 对应的 Task 记录是否存在且启用
2. **租户信息校验**：检查 JWT 令牌中的 tenantId 是否有效
3. **队列容量校验**：检查提交队列是否已满（容量10000）

---

## 6. 数据流转图

```
┌─────────────┐
│  用户请求   │  { taskId, priority?, parameters? }
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│  TaskSubmitService.submitTask()             │
│  1. 验证用户身份（JWT）                      │
│  2. 查询Task定义（taskMapper.selectOne）    │
│  3. 填充快照字段（taskName, taskType, ...） │
│  4. 填充上下文字段（tenantId, submitUserId）│
│  5. 生成taskInstanceId（雪花算法）          │
│  6. 入队（taskSubmitQueue.offer）           │
└──────┬──────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│  削峰队列（LinkedBlockingQueue）             │
│  容量：10000                                 │
│  超时：3秒                                   │
└──────┬──────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│  批量消费者（@Scheduled）                    │
│  1. 每秒拉取最多100个请求                    │
│  2. 转换为TaskInstance实体                  │
│  3. 批量插入数据库                           │
│  4. 失败重试/告警                            │
└─────────────────────────────────────────────┘
```

---

## 7. 后续建议

### 性能优化
1. **Task 定义缓存**：高频查询的 Task 定义可以加入缓存（Redis/Caffeine）
2. **批量插入优化**：考虑使用 MyBatis-Plus 的 `insertBatchSomeColumn` 提升性能

### 监控指标
1. **队列深度监控**：实时监控 `taskSubmitQueue.size()`
2. **提交成功率**：统计 `submitSuccessCount / submitTotalCount`
3. **入库延迟**：从入队到入库的时间差

### 容错增强
1. **死信队列**：入库失败超过重试次数后，写入死信队列
2. **幂等性保证**：基于 `instanceCode` 字段实现幂等性校验
3. **降级策略**：队列满时可考虑同步入库（绕过队列）

---

## 8. 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `TaskSubmitRequest.java` | 简化字段，添加校验注解，添加字段说明注释 |
| `Task.java` | 统一字段命名：`resourceRequire` → `resourceRequirement` |
| `TaskInstance.java` | 移除冗余字段 `triggerUserId` |
| `TaskSubmitServiceImpl.java` | 补全业务逻辑：Task查询、字段填充、批量消费 |

---

**重构完成时间**：2026-04-25  
**重构版本**：P3.1
