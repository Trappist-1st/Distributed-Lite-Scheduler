# Worker 回调终态代码速查表

## 🚀 核心调用流程（按执行顺序）

### Worker 端执行链
```
1. WorkerRunController.submitRun()
   ↓
2. WorkerRunService.submitAsync()
   ↓
3. WorkerRunService.executeSync()
   ├─ 获取执行器（DockerTaskTypeExecutor / ShellTaskTypeExecutor）
   └─ executor.execute(spec) → ExecutionResult
   ↓
4. ProcessExecutionHelper.execute()
   ├─ Process.waitFor(timeoutSeconds)
   ├─ if timeout → ExecutionResult.timedOut()
   └─ if success → ExecutionResult.success()
   ↓
5. SchedulerCallbackClient.report()
   ├─ 构建 TaskStatusTransitionBody
   ├─ HTTP POST 到 Scheduler
   └─ 请求头: X-Internal-Token: {internalToken}
```

### Scheduler 端接收链
```
1. TaskInstanceController.transitionStatus()
   ├─ 验证 X-Internal-Token
   └─ 调用 taskInstanceService.transitionStatus()
   ↓
2. TaskInstanceServiceImpl.transitionStatus()
   ├─ 状态验证 (toStatus ∈ [SUCCESS, FAILED, TIMEOUT])
   ├─ 幂等性检查
   ├─ 乐观锁版本号校验
   ├─ UPDATE task_instance (设置终态、时间、exitCode 等)
   └─ 返回 Result<TaskInstance>
   ↓
3. if (toStatus ∈ TERMINAL_STATUSES):
   ├─ publishTaskCompletionEvent() → Redis Stream
   └─ TaskInstanceTerminalHandler.afterTerminal()
      └─ ResourceSlotService.releaseForTaskInstanceSystem()
```

---

## 📍 按功能快速定位

### 1️⃣ 回调发送端（Worker）

| 功能 | 文件 | 类 | 关键方法 | 行号 |
|------|------|------|---------|------|
| **回调客户端** | `worker/.../service/SchedulerCallbackClient.java` | `SchedulerCallbackClient` | `report()` | 24 |
| **任务执行** | `worker/.../service/WorkerRunService.java` | `WorkerRunService` | `executeSync()` | 56 |
| **进程执行** | `worker/.../runtime/ProcessExecutionHelper.java` | `ProcessExecutionHelper` | `execute()` | 22 |
| **进程超时** | `worker/.../runtime/ProcessExecutionHelper.java` | `ProcessExecutionHelper` | execute() | **55-60** |
| **任务取消** | `worker/.../runtime/ProcessRunningTaskHandle.java` | `ProcessRunningTaskHandle` | `cancelForcibly()` | 16 |
| **任务注册表** | `worker/.../runtime/RunningTaskRegistry.java` | `RunningTaskRegistry` | `cancel()` | 24 |

### 2️⃣ 回调接收端（Scheduler）

| 功能 | 文件 | 类 | 关键方法 | 行号 |
|------|------|------|---------|------|
| **回调 API 入口** | `scheduler/.../controller/TaskInstanceController.java` | `TaskInstanceController` | `transitionStatus()` | 47 |
| **状态转换逻辑** | `scheduler/.../service/impl/TaskInstanceServiceImpl.java` | `TaskInstanceServiceImpl` | `transitionStatus()` | 73 |
| **幂等性处理** | `scheduler/.../service/impl/TaskInstanceServiceImpl.java` | `TaskInstanceServiceImpl` | transitionStatus() | **91-106** |
| **乐观锁更新** | `scheduler/.../service/impl/TaskInstanceServiceImpl.java` | `TaskInstanceServiceImpl` | transitionStatus() | **127-180** |
| **终态处理** | `scheduler/.../executor/completion/TaskInstanceTerminalHandler.java` | `TaskInstanceTerminalHandler` | `afterTerminal()` | 19 |
| **资源释放** | `scheduler/.../executor/completion/TaskInstanceTerminalHandler.java` | `TaskInstanceTerminalHandler` | afterTerminal() | **28** |
| **超时守护** | `scheduler/.../executor/watchdog/TaskTimeoutWatchdog.java` | `TaskTimeoutWatchdog` | `scanTimedOutTasks()` | 26 |
| **URL 构建** | `scheduler/.../service/executor/WorkerRunRequestFactory.java` | `WorkerRunRequestFactory` | `fromRunSpec()` | 26 |

---

## 🔍 状态转换关键代码片段

### ExecutionResult 的四种终态
```java
// success: exitCode == 0
ExecutionResult.success(0, stdout, stderr)
   → toStatus = "SUCCESS"

// failure: exitCode != 0
ExecutionResult.failure(exitCode, stdout, stderr, errorMsg)
   → toStatus = "FAILED"

// timedOut: process.waitFor() 超时
ExecutionResult.timedOut("任务执行超时...")
   → toStatus = "TIMEOUT"

// configurationError: 配置问题
ExecutionResult.configurationError("不支持的任务类型")
   → 不进入终态，直接回调失败
```

### 状态转换映射表（关键代码）
```java
// TaskInstanceServiceImpl.java L307-316

private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
    "PENDING",   Set.of("RUNNING", "CANCELLED"),
    "RUNNING",   Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"),  // ← Worker 回调的目标
    "FAILED",    Set.of("PENDING"),        // 重试
    "TIMEOUT",   Set.of("PENDING"),        // 重试
    "SUCCESS",   Set.of(),                 // 终态，无后续
    "CANCELLED", Set.of()                  // 终态，无后续
);

private static final Set<String> TERMINAL_STATUSES = Set.of(
    "SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"
);
```

### HTTP 回调请求格式
```bash
POST /api/internal/task-instances/{id}/status
X-Internal-Token: {internalToken}
Content-Type: application/json

{
  "fromStatus": "RUNNING",
  "toStatus": "SUCCESS|FAILED|TIMEOUT",
  "triggerSource": "WORKER",
  "reason": "执行成功|执行失败|执行超时",
  "exitCode": 0 or non-zero,
  "errorMessage": "optional error details"
}
```

---

## ⚙️ 配置项查询

| 配置项 | 用途 | 位置 | 示例值 |
|------|------|------|---------|
| `internal.api.token` | Worker 回调认证令牌 | application.properties | `abc123xyz` |
| `scheduler.base-url` | Scheduler 公网地址（供 Worker 回调） | TaskExecutorProperties | `http://scheduler:8080` |
| `task.executor.timeout-scan-interval-ms` | 超时守护扫描间隔 | application.properties | `30000` (30s) |
| `worker.executor.work-dir` | Worker 工作目录 | WorkerProperties | `/data/worker` |
| `worker.executor.output-max-chars` | 标准输出最大字符数 | WorkerProperties | `10485760` (10MB) |

---

## 🎯 常用追踪路径

### 追踪1：为什么我的 SUCCESS 回调没有生效？
```
1. 检查 Worker 是否正确生成 ExecutionResult.success()
   → ProcessExecutionHelper.java L65

2. 检查 SchedulerCallbackClient 是否成功发送回调
   → SchedulerCallbackClient.java L32-40

3. 检查 Scheduler 是否验证了 X-Internal-Token
   → TaskInstanceController.java L52

4. 查看状态是否真的变为 SUCCESS
   → task_instance 表的 status 字段

5. 检查是否有幂等性被触发
   → TaskInstanceServiceImpl.java L91-106
```

### 追踪2：为什么超时监控没有工作？
```
1. 检查 TaskTimeoutWatchdog 是否被启用
   → TaskTimeoutWatchdog.java L30
   → 检查 task.executor.timeout-scan-enabled 配置

2. 检查任务的 timeout_seconds 是否设置
   → Task 表的 timeout_seconds 字段

3. 检查扫描间隔
   → 配置 task.executor.timeout-scan-interval-ms
   → 默认 30s

4. 查看是否真的被扫描到超时
   → TaskInstanceMapper.selectTimedOutRunningTasks()
   → 查询条件：status='RUNNING' AND TIMESTAMPDIFF(SECOND, start_time, NOW()) > timeout_seconds
```

### 追踪3：为什么任务资源没有释放？
```
1. 检查任务是否真的进入了终态
   → task_instance 表的 status 字段

2. 检查 afterTerminal() 是否被调用
   → TaskInstanceServiceImpl.java L188
   → 设置断点或日志

3. 检查 ResourceSlotService.releaseForTaskInstanceSystem()
   → 是否成功执行
   → 是否有异常被吃掉

4. 查看 resource_slot 表
   → 确认槽位是否被释放
```

---

## 📊 数据库表关联

```
task_instance
├─ id (PK)
├─ task_id (FK → task.id)
├─ status (PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT)
├─ start_time
├─ end_time
├─ duration_ms
├─ exit_code
├─ error_message
├─ version (乐观锁)
└─ retry_count

task_status_change_log
├─ id (PK)
├─ task_instance_id (FK → task_instance.id)
├─ from_status
├─ to_status
├─ trigger_source (SCHEDULER/WORKER/SYSTEM/API)
├─ reason
├─ operator_user_id
└─ created_at

resource_slot
├─ id (PK)
├─ resource_node_id (FK)
├─ task_instance_id (FK → task_instance.id, 可为 null)
├─ status (ALLOCATED/RELEASED)
└─ released_reason (SUCCESS/FAILED/TIMEOUT/CANCELLED)
```

---

## 🔐 并发安全性检查清单

- [ ] 乐观锁版本号是否正确校验？
  - 位置：TaskInstanceServiceImpl.java L128
  
- [ ] 迟到回调是否被正确忽略？
  - 位置：TaskInstanceServiceImpl.java L100-104
  
- [ ] 运行中的任务是否被正确注册/注销？
  - 位置：ProcessExecutionHelper.java L30, L62
  
- [ ] 取消请求是否原子操作？
  - 位置：RunningTaskRegistry.java L24
  
- [ ] 资源释放是否是幂等的？
  - 位置：TaskInstanceTerminalHandler.java L28

---

## 📝 调试技巧

### 1. 启用详细日志
```properties
logging.level.com.imperium.distributed_lite_scheduler_v1.service.impl.TaskInstanceServiceImpl=DEBUG
logging.level.com.imperium.distributed_lite_worker.service.SchedulerCallbackClient=DEBUG
```

### 2. 查看具体的状态转换日志
```sql
SELECT 
  til.id, til.task_instance_id, 
  til.from_status, til.to_status, 
  til.trigger_source, til.reason, 
  til.created_at
FROM task_status_change_log til
WHERE til.task_instance_id = {taskInstanceId}
ORDER BY til.created_at DESC;
```

### 3. 查看乐观锁冲突
```java
// 如果看到 "状态更新冲突，请重试" 错误
// 说明多个线程同时试图改变同一任务的状态
// 检查是否有并发的 API 调用或内部流程冲突
```

### 4. 验证回调参数
```bash
# 在 Scheduler 日志中查找
# "已回调调度中心 taskInstanceId 目标状态={}" - SchedulerCallbackClient.java L38

# 或手动测试回调
curl -v -X POST http://scheduler:8080/api/internal/task-instances/123/status \
  -H "X-Internal-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{...}'
```

