# Worker 回调终态代码文件结构树

## 📂 项目中涉及的所有源文件位置

```
Distributed_Lite_Scheduler_V1/
│
├── worker/
│   └── src/main/java/com/imperium/distributed_lite_worker/
│       │
│       ├── controller/
│       │   └── WorkerRunController.java ⭐
│       │       ├─ POST /api/worker/runs              (接收调度中心下发的任务)
│       │       └─ POST /api/worker/runs/{id}/cancel  (接收任务取消请求)
│       │
│       ├── service/
│       │   ├── WorkerRunService.java ⭐⭐⭐
│       │   │   ├─ submitAsync()          → 异步执行任务
│       │   │   ├─ executeSync()          → 同步执行逻辑
│       │   │   └─ cancel()               → 取消任务
│       │   │
│       │   └── SchedulerCallbackClient.java ⭐⭐⭐
│       │       ├─ report()               → 发送回调到调度中心
│       │       └─ buildBody()            → 构建状态转换请求体
│       │
│       ├── executor/
│       │   ├── ExecutionResult.java ⭐
│       │   │   ├─ success()              → 成功的执行结果
│       │   │   ├─ failure()              → 失败的执行结果
│       │   │   ├─ timedOut()             → 超时的执行结果
│       │   │   └─ configurationError()   → 配置错误
│       │   │
│       │   ├── LocalRunSpec.java
│       │   │   (进程运行规范)
│       │   │
│       │   ├── WorkerTaskTypeExecutor.java
│       │   │   (执行器接口)
│       │   │
│       │   ├── impl/
│       │   │   ├── DockerTaskTypeExecutor.java
│       │   │   │   └─ execute()          → 执行 Docker 任务
│       │   │   │
│       │   │   └── ShellTaskTypeExecutor.java
│       │   │       └─ execute()          → 执行 Shell 任务
│       │   │
│       │   └── WorkerTaskTypeExecutorRegistry.java
│       │       (执行器注册表，根据 taskType 查找)
│       │
│       ├── runtime/
│       │   ├── ProcessExecutionHelper.java ⭐⭐
│       │   │   ├─ execute()              → 执行进程的核心方法
│       │   │   │   ├─ 启动进程
│       │   │   │   ├─ 注册到 RunningTaskRegistry
│       │   │   │   ├─ 等待完成或超时
│       │   │   │   ├─ 返回 ExecutionResult
│       │   │   │   └─ 注销从 RunningTaskRegistry
│       │   │   └─ readStream()           → 读取进程输出流
│       │   │
│       │   ├── RunningTaskRegistry.java ⭐⭐
│       │   │   ├─ register()             → 注册运行中的任务
│       │   │   ├─ unregister()           → 注销任务
│       │   │   └─ cancel()               → 取消任务 (强制终止进程)
│       │   │
│       │   ├── RunningTaskHandle.java
│       │   │   (任务句柄接口)
│       │   │
│       │   └── ProcessRunningTaskHandle.java
│       │       └─ cancelForcibly()       → 强制杀死进程
│       │
│       └── dto/
│           ├── WorkerRunRequest.java ⭐
│           │   ├─ taskInstanceId
│           │   ├─ taskType
│           │   ├─ command
│           │   ├─ timeoutSeconds
│           │   └─ callback (WorkerRunCallback)
│           │
│           ├── WorkerRunCallback.java ⭐
│           │   ├─ statusTransitionUrl   → 调度中心的回调 URL
│           │   └─ internalToken         → 认证令牌
│           │
│           └── TaskStatusTransitionBody.java ⭐⭐
│               ├─ fromStatus            → 来源状态 (通常为 RUNNING)
│               ├─ toStatus              → 目标状态 (SUCCESS/FAILED/TIMEOUT)
│               ├─ triggerSource         → 触发源 (WORKER)
│               ├─ reason                → 原因
│               ├─ exitCode              → 进程退出码
│               └─ errorMessage          → 错误消息
│
└── scheduler/
    └── src/main/java/com/imperium/distributed_lite_scheduler_v1/
        │
        ├── controller/
        │   └── TaskInstanceController.java ⭐⭐
        │       └─ POST /{id}/status              (接收 Worker 回调)
        │           ├─ 验证 X-Internal-Token
        │           └─ 调用 taskInstanceService.transitionStatus()
        │
        ├── service/
        │   ├── TaskInstanceService.java
        │   │   └─ transitionStatus()    (接口定义)
        │   │
        │   └── impl/
        │       └── TaskInstanceServiceImpl.java ⭐⭐⭐
        │           ├─ transitionStatus() → 状态转换核心逻辑
        │           │   ├─ 状态验证
        │           │   ├─ 幂等性检查
        │           │   ├─ 乐观锁更新
        │           │   ├─ 终态处理
        │           │   └─ 发布完成事件
        │           │
        │           ├─ persistStatusChangeLog()  → 记录状态变化
        │           ├─ publishTaskCompletionEvent() → 发布 Redis Stream 事件
        │           └─ (静态常量)
        │               ├─ TERMINAL_STATUSES     → {SUCCESS, FAILED, TIMEOUT, CANCELLED}
        │               └─ ALLOWED_TRANSITIONS   → 允许的状态转移表
        │
        ├── executor/
        │   ├── WorkerRunRequestFactory.java ⭐⭐
        │   │   └─ fromRunSpec()         → 构建 Worker 请求
        │   │       ├─ 生成 statusTransitionUrl
        │   │       └─ 生成 internalToken
        │   │
        │   ├── TaskExecutionReporter.java
        │   │   (任务执行报告器)
        │   │
        │   ├── completion/
        │   │   └── TaskInstanceTerminalHandler.java ⭐⭐
        │   │       └─ afterTerminal()   → 终态后处理
        │   │           ├─ 释放资源槽位
        │   │           └─ 记录释放原因
        │   │
        │   └── watchdog/
        │       └── TaskTimeoutWatchdog.java ⭐
        │           ├─ scanTimedOutTasks() → 定时扫描超时任务
        │           └─ handleTimedOut()    → 处理超时任务
        │
        ├── model/
        │   ├── dto/
        │   │   ├── InternalTaskInstanceStatusTransitionRequest.java ⭐
        │   │   │   (回调请求数据结构)
        │   │   │   ├─ fromStatus
        │   │   │   ├─ toStatus
        │   │   │   ├─ triggerSource
        │   │   │   ├─ reason
        │   │   │   ├─ exitCode
        │   │   │   └─ errorMessage
        │   │   │
        │   │   └── worker/
        │   │       ├── WorkerRunCallback.java
        │   │       └── WorkerRunRequest.java
        │   │
        │   └── entity/
        │       ├── TaskInstance.java
        │       │   ├─ id
        │       │   ├─ status (PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED)
        │       │   ├─ start_time
        │       │   ├─ end_time
        │       │   ├─ duration_ms
        │       │   ├─ exit_code
        │       │   ├─ error_message
        │       │   ├─ version (乐观锁)
        │       │   └─ retry_count
        │       │
        │       └── TaskStatusChangeLog.java
        │           ├─ task_instance_id
        │           ├─ from_status
        │           ├─ to_status
        │           ├─ trigger_source
        │           ├─ reason
        │           └─ created_at
        │
        ├── mapper/
        │   ├── TaskInstanceMapper.java
        │   │   ├─ selectTimedOutRunningTasks() → 查询超时任务
        │   │   └─ update()                      → 乐观锁更新
        │   │
        │   └── TaskStatusChangeLogMapper.java
        │       └─ insert()                  → 插入状态变化日志
        │
        ├── config/
        │   ├── properties/
        │   │   ├── TaskExecutorProperties.java
        │   │   │   ├─ scheduler.base-url
        │   │   │   ├─ internal.api.token
        │   │   │   └─ task.executor.timeout-scan-*
        │   │   │
        │   │   └── WorkerRestClientConfig.java
        │   │       (Worker 通信配置)
        │   │
        │   └── OpenApiConfig.java
        │       (API 文档配置，标记内部接口)
        │
        └── constant/
            └── TaskInstanceStatus.java
                ├─ PENDING
                ├─ RUNNING
                ├─ SUCCESS
                ├─ FAILED
                ├─ TIMEOUT
                ├─ CANCELLED
                └─ isFinalState() → 是否为终态
```

---

## 🔗 关键类间调用关系

```
Worker 端调用链：
──────────────────

WorkerRunController
        ↓
WorkerRunService.submitAsync()
        ↓
    ┌───────────────────┐
    │ 异步执行           │
    ├───────────────────┤
    │ WorkerRunService │
    │   .executeSync()  │
    └───────────────────┘
        │
        ├─→ 获取执行器：
        │   WorkerTaskTypeExecutorRegistry.resolve(taskType)
        │       ↓
        │   DockerTaskTypeExecutor / ShellTaskTypeExecutor
        │       ↓
        │   (调用) execute(LocalRunSpec)
        │
        └─→ ProcessExecutionHelper.execute()
            ├─ ProcessBuilder.start()
            ├─ RunningTaskRegistry.register()
            ├─ process.waitFor(timeoutSeconds)
            │   ├─ 超时 → ExecutionResult.timedOut()
            │   ├─ 成功 → ExecutionResult.success()
            │   └─ 失败 → ExecutionResult.failure()
            ├─ 返回 ExecutionResult
            └─ RunningTaskRegistry.unregister()

        返回 ExecutionResult
        ↓
WorkerRunService.executeSync() (catch 和 finally)
        ↓
SchedulerCallbackClient.report(callback, result)
    ├─ buildBody(result)
    │   ├─ if (result.isTimedOut()) → toStatus = "TIMEOUT"
    │   ├─ if (result.isSuccess()) → toStatus = "SUCCESS"
    │   └─ else → toStatus = "FAILED"
    │
    └─ HTTP POST {callback.statusTransitionUrl}
        └─ Header: X-Internal-Token = {callback.internalToken}
```

---

```
Scheduler 端调用链：
────────────────────

HTTP 请求 (来自 Worker)
        ↓
TaskInstanceController.transitionStatus()
    ├─ 验证 X-Internal-Token 请求头
    └─ 调用 TaskInstanceService.transitionStatus()
        ↓
TaskInstanceServiceImpl.transitionStatus()
    ├─ 参数验证：
    │   ├─ 状态有效性
    │   ├─ 转移合法性
    │   └─ 任务存在性
    │
    ├─ 幂等性检查：
    │   ├─ 重复请求 → 直接返回成功
    │   ├─ 迟到回调 → 忽略
    │   └─ 非法转移 → 返回 CONFLICT
    │
    ├─ 乐观锁版本号校验
    │
    ├─ 构造 LambdaUpdateWrapper
    │   ├─ if (toStatus ∈ TERMINAL_STATUSES)
    │   │   ├─ 设置 endTime = now
    │   │   ├─ 计算 durationMs
    │   │   ├─ 设置 exitCode
    │   │   └─ 设置 errorMessage
    │   └─ version = version + 1
    │
    ├─ 执行 UPDATE (乐观锁)
    │
    ├─ persistStatusChangeLog()
    │   └─ TaskStatusChangeLogMapper.insert()
    │
    ├─ 如果转为终态：
    │   ├─ publishTaskCompletionEvent()
    │   │   └─ 发布 Redis Stream 事件 (DAG 工作流驱动)
    │   │
    │   └─ TaskInstanceTerminalHandler.afterTerminal()
    │       ├─ 确定释放原因
    │       └─ ResourceSlotService.releaseForTaskInstanceSystem()
    │           └─ 释放资源槽位
    │
    └─ 返回 Result<TaskInstance>
        ↓
HTTP 响应 (返回给 Worker)
```

---

## 📊 执行结果与终态状态的映射

```
ProcessExecutionHelper.execute() 返回：
│
├─ ExecutionResult.success(exitCode=0, ...)
│   └─ TaskStatusTransitionBody.toStatus = "SUCCESS"
│       └─ TaskInstanceServiceImpl: status → "SUCCESS"
│           └─ TERMINAL_STATUSES ✓
│           └─ 释放资源: releaseReason = "SUCCESS"
│
├─ ExecutionResult.failure(exitCode≠0, ..., errorMsg)
│   └─ TaskStatusTransitionBody.toStatus = "FAILED"
│       └─ TaskInstanceServiceImpl: status → "FAILED"
│           └─ TERMINAL_STATUSES ✓
│           └─ 释放资源: releaseReason = "FAILED"
│
├─ ExecutionResult.timedOut(errorMsg)
│   ├─ 来源1：process.waitFor() 返回 false
│   │   └─ ProcessExecutionHelper L60
│   │
│   └─ 来源2：TaskTimeoutWatchdog.scanTimedOutTasks()
│       └─ 系统主动扫描超时 (triggerSource="SYSTEM")
│       └─ TaskStatusTransitionBody.toStatus = "TIMEOUT"
│           └─ TaskInstanceServiceImpl: status → "TIMEOUT"
│               └─ TERMINAL_STATUSES ✓
│               └─ 释放资源: releaseReason = "TIMEOUT"
│
└─ ExecutionResult.configurationError(errorMsg)
    └─ 不进入终态，立即返回失败
```

---

## 🎯 核心文件星级标注

| 星级 | 含义 | 文件数量 |
|------|------|---------|
| ⭐⭐⭐ | 最核心，必须理解 | 3 个 |
| ⭐⭐ | 很重要，应该理解 | 6 个 |
| ⭐ | 重要，可以了解 | 9 个 |

**⭐⭐⭐ 必读文件：**
1. WorkerRunService.java - 任务执行与回调的连接点
2. SchedulerCallbackClient.java - 回调发送
3. TaskInstanceServiceImpl.java - 状态转换核心逻辑

**⭐⭐ 重要文件：**
1. ProcessExecutionHelper.java - 进程执行与超时处理
2. TaskInstanceController.java - 回调 API 入口
3. WorkerRunRequestFactory.java - 回调 URL 构建
4. TaskInstanceTerminalHandler.java - 终态处理（资源释放）
5. TaskTimeoutWatchdog.java - 超时监控
6. RunningTaskRegistry.java - 并发任务管理

