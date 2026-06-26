# Worker 执行器回调终态代码定位完整清单

## 概述
本文档完整定位了项目中 Worker 执行器回调调度中心实现终态转换的所有相关代码。整个流程分为：
1. **Worker 端**：执行任务并回调结果
2. **Scheduler 端**：接收回调并转换任务状态
3. **终态处理**：资源释放、事件发布等

---

## 第一部分：Worker 端代码（回调发送方）

### 1. 回调客户端核心类
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/service/SchedulerCallbackClient.java`

**关键方法：**
- `report(WorkerRunCallback callback, ExecutionResult result)` - L24
  - 构建 `TaskStatusTransitionBody`
  - 发送 HTTP POST 请求到调度中心
  - 添加 `X-Internal-Token` 请求头进行身份验证

**状态映射逻辑：** L45-70
- `ExecutionResult.isTimedOut()` → toStatus = **TIMEOUT** （L47）
- `ExecutionResult.isSuccess()` → toStatus = **SUCCESS** （L54）
- 其他（failure） → toStatus = **FAILED** （L61）

### 2. 任务执行与回调入口
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/service/WorkerRunService.java`

**关键方法：**
- `submitAsync(WorkerRunRequest request)` - L40
  - 异步提交任务到线程池执行
  - 捕获 `RejectedExecutionException` 并回调配置错误 → L48

- `executeSync(WorkerRunRequest request)` - L56
  - 同步执行任务的具体逻辑
  - 获取对应的任务类型执行器：L64
  - 执行任务并获得 `ExecutionResult`：L75
  - **关键：** 执行成功或失败后调用 → `schedulerCallbackClient.report(request.getCallback(), result)` - L76
  - 异常情况也会回调：L79

- `cancel(Long taskInstanceId)` - L52
  - 取消正在执行的任务

### 3. 请求/响应数据结构

#### 3.1 回调信息
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/dto/WorkerRunCallback.java`

```java
@Data
public class WorkerRunCallback {
    private String statusTransitionUrl;      // 调度中心的状态转换 API URL
    private String internalToken;             // 内部认证令牌
}
```

#### 3.2 任务执行请求
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/dto/WorkerRunRequest.java`

```java
@Data
public class WorkerRunRequest {
    private Long taskInstanceId;
    private Long resourceNodeId;
    private String taskType;
    private String command;
    private Integer timeoutSeconds;
    private String executorConfig;
    private Map<String, Object> parameters;
    @NotNull @Valid
    private WorkerRunCallback callback;        // ← 包含回调配置
}
```

#### 3.3 状态转换请求体
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/dto/TaskStatusTransitionBody.java`

```java
@Data
@Builder
public class TaskStatusTransitionBody {
    private String fromStatus;          // 通常为 "RUNNING"
    private String toStatus;            // SUCCESS / FAILED / TIMEOUT
    private String triggerSource;       // 固定为 "WORKER"
    private String reason;              // 如 "执行成功"、"执行超时"
    private Long operatorUserId;
    private Integer exitCode;
    private String errorMessage;
}
```

#### 3.4 执行结果
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/executor/ExecutionResult.java`

```java
@Value
@Builder
public class ExecutionResult {
    boolean success;
    int exitCode;
    String stdout;
    String stderr;
    String errorMessage;
    boolean timedOut;

    // 工厂方法
    public static ExecutionResult success(int exitCode, String stdout, String stderr)
    public static ExecutionResult failure(int exitCode, String stdout, String stderr, String errorMessage)
    public static ExecutionResult timedOut(String errorMessage)
    public static ExecutionResult configurationError(String errorMessage)
}
```

### 4. 任务执行核心

#### 4.1 进程执行帮助类
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/runtime/ProcessExecutionHelper.java`

**关键方法：** `execute(Long taskInstanceId, Path workDirectory, Integer timeoutSeconds, ProcessBuilder processBuilder)` - L22

**超时处理逻辑：** L55-60
- 如果进程超时，调用 `process.destroyForcibly()`
- 返回 `ExecutionResult.timedOut("任务执行超时...")` - L60

**正常完成流程：** L53
- 获取进程的 `exitCode`
- exitCode == 0 → `ExecutionResult.success(...)` - L65
- exitCode != 0 → `ExecutionResult.failure(...)` - L68

#### 4.2 运行任务注册表（并发管理）
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/runtime/RunningTaskRegistry.java`

```java
@Component
public class RunningTaskRegistry {
    private final ConcurrentHashMap<Long, RunningTaskHandle> handles = new ConcurrentHashMap<>();

    public void register(Long taskInstanceId, RunningTaskHandle handle)      // L11
    public void unregister(Long taskInstanceId)                             // L17
    public boolean cancel(Long taskInstanceId)                              // L24
}
```

**使用场景：**
- `register()` - ProcessExecutionHelper L30：进程启动时注册
- `unregister()` - ProcessExecutionHelper L62（finally 块）：进程结束时注销
- `cancel()` - WorkerRunService L53：取消时强制终止进程

#### 4.3 进程任务句柄
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/runtime/ProcessRunningTaskHandle.java`

```java
@Slf4j
@RequiredArgsConstructor
public class ProcessRunningTaskHandle implements RunningTaskHandle {
    private final Long taskInstanceId;
    private final Process process;

    @Override
    public void cancelForcibly() {  // L16
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("Worker 终止任务进程 taskInstanceId={}", taskInstanceId);
        process.destroyForcibly();  // ← 强制终止进程
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 5. Worker 控制器
**文件：** `worker/src/main/java/com/imperium/distributed_lite_worker/controller/WorkerRunController.java`

```java
@PostMapping("/runs")  // L34
public ResponseEntity<WorkerRunAcceptedResponse> submitRun(
        @RequestBody @Valid WorkerRunRequest request) {
    workerRunService.submitAsync(request);  // ← 异步执行，立即返回 202
    return ResponseEntity.status(HttpStatus.ACCEPTED)...
}

@PostMapping("/runs/{taskInstanceId}/cancel")  // L41
public ResponseEntity<WorkerRunAcceptedResponse> cancelRun(
        @PathVariable Long taskInstanceId) {
    boolean cancelled = workerRunService.cancel(taskInstanceId);
}
```

**文档注释：** L32
> "立即返回 202，任务在线程池中执行；完成后回调调度中心内部状态 API"

---

## 第二部分：Scheduler 端代码（回调接收方）

### 1. 内部回调 API 入口
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/controller/TaskInstanceController.java`

```java
@PostMapping("/{id}/status")  // L47
public Result<TaskInstance> transitionStatus(
        @PathVariable("id") Long id,
        @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
        @RequestBody @Valid InternalTaskInstanceStatusTransitionRequest request) {
    
    // 验证内部令牌
    if (!internalApiToken.equals(internalToken)) {  // L52
        return Result.failure(ResultCode.FORBIDDEN, "内部接口鉴权失败");
    }
    
    return taskInstanceService.transitionStatus(id, request);  // L55
}
```

**关键注释：** L43-44
> "Worker 执行完成后回调，将 RUNNING 更新为 SUCCESS/FAILED/TIMEOUT 等"

**类级注释：** L27
> "Worker 回调等内部状态流转接口（非 JWT，使用 X-Internal-Token）"

### 2. 回调请求数据结构
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/InternalTaskInstanceStatusTransitionRequest.java`

```java
public record InternalTaskInstanceStatusTransitionRequest(
    @NotBlank String fromStatus,           // 来源状态（通常为 RUNNING）
    @NotBlank String toStatus,             // 目标状态（SUCCESS/FAILED/TIMEOUT）
    @NotBlank String triggerSource,        // 触发源（WORKER/SCHEDULER/SYSTEM/API）
    @Size(max = 500) String reason,        // 原因描述
    @Min(0) Long operatorUserId,
    Integer exitCode,
    @Size(max = 2000) String errorMessage  // 错误消息
) {}
```

### 3. 状态转换核心业务逻辑
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/service/impl/TaskInstanceServiceImpl.java`

#### 3.1 主方法
**方法：** `transitionStatus(Long taskInstanceId, InternalTaskInstanceStatusTransitionRequest request)` - L73

**状态验证逻辑：** L74-83
```java
// 1. 状态有效性检查
if (!isKnownStatus(fromStatus) || !isKnownStatus(toStatus))  // L74

// 2. 状态转移合法性检查
if (!canTransition(fromStatus, toStatus))  // L77

// 3. 任务存在性检查
TaskInstance current = baseMapper.selectById(taskInstanceId);  // L87

// 4. 当前状态匹配检查 + 幂等性处理
String currentStatus = normalizeStatus(current.getStatus());
if (!fromStatus.equals(currentStatus))  // L89
```

#### 3.2 幂等性处理（关键特性）
**代码：** L91-106

```java
// 已处于终态且是幂等重复请求
if (currentStatus.equals(toStatus) && TERMINAL_STATUSES.contains(currentStatus)) {
    return Result.success(current);  // L92
}

// 迟到的 Worker 回调（已处于终态，忽略）
if (TERMINAL_STATUSES.contains(currentStatus) && isWorkerTrigger(request.triggerSource())) {
    log.debug("忽略迟到 Worker 回调 taskInstanceId={} current={} requested={}",
        taskInstanceId, currentStatus, toStatus);  // L100-104
    return Result.success(current);
}

// 任务已处于终态，不允许其他状态转移
if (TERMINAL_STATUSES.contains(currentStatus)) {
    return Result.failure(ResultCode.CONFLICT, 
        "任务已处于终态 " + currentStatus + "，无法流转为 " + toStatus);  // L105-108
}
```

#### 3.3 乐观锁更新（并发安全）
**代码：** L127-173

关键点：使用版本号实现乐观锁
```java
LambdaUpdateWrapper<TaskInstance> uw = new LambdaUpdateWrapper<TaskInstance>()
    .eq(TaskInstance::getId, taskInstanceId)
    .eq(TaskInstance::getStatus, current.getStatus())
    .eq(TaskInstance::getVersion, current.getVersion())  // ← 版本号匹配
    .set(TaskInstance::getStatus, toStatus);

// 转为 RUNNING 状态
if (STATUS_RUNNING.equals(toStatus)) {  // L140-146
    uw.set(TaskInstance::getStartTime, now);
    uw.set(TaskInstance::getEndTime, null);
    uw.set(TaskInstance::getDurationMs, null);
    uw.set(TaskInstance::getExitCode, null);
    uw.set(TaskInstance::getErrorMessage, null);
}

// 转为终态
if (TERMINAL_STATUSES.contains(toStatus)) {  // L149-166
    uw.set(TaskInstance::getEndTime, now);
    
    // 计算持续时间
    if (current.getStartTime() != null) {
        long durationMs = Math.max(0L, Duration.between(current.getStartTime(), now).toMillis());
        uw.set(TaskInstance::getDurationMs, durationMs);
    }
    
    // 根据终态类型设置 exitCode 和 errorMessage
    if (STATUS_SUCCESS.equals(toStatus)) {  // L155
        uw.set(TaskInstance::getExitCode, request.exitCode() != null ? request.exitCode() : 0);
        uw.set(TaskInstance::getErrorMessage, null);
    } else if (STATUS_FAILED.equals(toStatus) || STATUS_TIMEOUT.equals(toStatus)) {  // L158
        uw.set(TaskInstance::getExitCode, request.exitCode());
        uw.set(TaskInstance::getErrorMessage, 
            StringUtils.hasText(request.errorMessage()) ? request.errorMessage().trim() : null);
    }
}
```

**执行更新：** L176-180
```java
int updated = baseMapper.update(null, uw);  // L176
if (updated != 1) {  // L178
    return Result.failure(ResultCode.CONFLICT, "状态更新冲突，请重试");
}
```

#### 3.4 状态转换允许表
**代码：** L307-316

```java
private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
    STATUS_PENDING, Set.of(STATUS_RUNNING, STATUS_CANCELLED),
    STATUS_RUNNING, Set.of(STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED, STATUS_TIMEOUT),  // ← 关键：从 RUNNING 转向终态
    STATUS_FAILED, Set.of(STATUS_PENDING),      // ← 重试：FAILED → PENDING
    STATUS_TIMEOUT, Set.of(STATUS_PENDING),     // ← 重试：TIMEOUT → PENDING
    STATUS_SUCCESS, Set.of(),                   // ← 终态，不允许转移
    STATUS_CANCELLED, Set.of()                  // ← 终态，不允许转移
);

private static final Set<String> TERMINAL_STATUSES = Set.of(
    STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED, STATUS_TIMEOUT
);
```

#### 3.5 终态后处理
**代码：** L183-188

```java
// 如果转换到终止状态，发布事件到 Redis Stream（异步，不阻塞返回）
if (TERMINAL_STATUSES.contains(toStatus)) {  // L186
    publishTaskCompletionEvent(latest);         // L187：发布完成事件
    taskInstanceTerminalHandler.afterTerminal(latest, toStatus);  // L188：终态处理
}

return Result.success(latest);
```

### 4. 终态处理器
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/service/executor/completion/TaskInstanceTerminalHandler.java`

```java
@Component
@RequiredArgsConstructor
public class TaskInstanceTerminalHandler {
    private final ResourceSlotService resourceSlotService;

    public void afterTerminal(TaskInstance taskInstance, String terminalStatus) {  // L19
        if (taskInstance == null || taskInstance.getId() == null) {
            return;
        }
        
        String releaseReason = mapReleaseReason(terminalStatus);  // L24
        if (releaseReason == null) {
            return;
        }
        
        try {
            // ← 核心：释放资源槽位
            resourceSlotService.releaseForTaskInstanceSystem(
                taskInstance.getId(), releaseReason);  // L28
        } catch (Exception e) {
            log.error("任务终态资源释放异常...", e);
        }
    }

    private static String mapReleaseReason(String terminalStatus) {  // L33
        return switch (terminalStatus.toUpperCase()) {
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            case "TIMEOUT" -> "TIMEOUT";
            case "CANCELLED" -> "CANCELLED";
            default -> null;
        };
    }
}
```

**关键特性：**
- 任务进入终态后立即释放所占用的资源槽位
- 确保资源能够被其他任务使用

### 5. 状态变化日志记录
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/service/impl/TaskInstanceServiceImpl.java` - L258-271

```java
private void persistStatusChangeLog(Long taskInstanceId,
                                    String fromStatus,
                                    String toStatus,
                                    InternalTaskInstanceStatusTransitionRequest request) {
    TaskStatusChangeLog log = new TaskStatusChangeLog();
    log.setTaskInstanceId(taskInstanceId);
    log.setFromStatus(fromStatus);
    log.setToStatus(toStatus);
    log.setTriggerSource(request.triggerSource().trim().toUpperCase(Locale.ROOT));
    log.setReason(StringUtils.hasText(request.reason()) ? request.reason().trim() : null);
    log.setOperatorUserId(request.operatorUserId());
    taskStatusChangeLogMapper.insert(log);
}
```

### 6. 超时监控守护
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/service/executor/watchdog/TaskTimeoutWatchdog.java`

```java
@Scheduled(fixedDelayString = "${task.executor.timeout-scan-interval-ms:30000}")
public void scanTimedOutTasks() {  // L26
    List<TaskInstance> timedOut = taskInstanceMapper.selectTimedOutRunningTasks(...);
    for (TaskInstance instance : timedOut) {
        handleTimedOut(instance);
    }
}

private void handleTimedOut(TaskInstance instance) {  // L38
    // 1. 取消 Worker 端的任务
    taskExecutionCancelService.cancelRunningTask(
        taskInstanceId, instance.getResourceNodeId());  // L39
    
    // 2. 转换为 TIMEOUT 状态（系统主动触发，而非 Worker 回调）
    InternalTaskInstanceStatusTransitionRequest request =
        new InternalTaskInstanceStatusTransitionRequest(
            TaskInstanceStatus.RUNNING.getCode(),
            TaskInstanceStatus.TIMEOUT.getCode(),
            "SYSTEM",  // ← 触发源为 SYSTEM
            "调度器超时守护",
            null, -1,
            "任务执行超过 timeoutSeconds 限制");
    
    Result<TaskInstance> result = taskInstanceService.transitionStatus(taskInstanceId, request);  // L51
}
```

### 7. 回调 URL 和令牌配置
**文件：** `scheduler/src/main/java/com/imperium/distributed_lite_scheduler_v1/service/executor/WorkerRunRequestFactory.java`

```java
@Component
@RequiredArgsConstructor
public class WorkerRunRequestFactory {

    private final TaskExecutorProperties taskExecutorProperties;

    @Value("${internal.api.token:}")
    private String internalApiToken;  // L22：内部 API 令牌

    public WorkerRunRequest fromRunSpec(RunSpec runSpec, ResourceNode node) {
        String baseUrl = trimTrailingSlash(taskExecutorProperties.getSchedulerPublicBaseUrl());
        
        // 构建回调 URL
        String statusUrl = baseUrl + "/api/internal/task-instances/" 
            + runSpec.getTaskInstanceId() + "/status";  // L27

        WorkerRunCallback callback = WorkerRunCallback.builder()
            .statusTransitionUrl(statusUrl)  // ← 设置回调 URL
            .internalToken(internalApiToken)  // ← 设置认证令牌
            .build();

        return WorkerRunRequest.builder()
            // ... 其他字段
            .callback(callback)
            .build();
    }
}
```

**配置项：**
- `scheduler.base-url` → Worker 回调地址基础 URL
- `internal.api.token` → 内部认证令牌

---

## 第三部分：完整流程图

```
┌─ Worker 端 ─────────────────────────┐
│                                      │
│  1. 接收任务请求                      │
│     WorkerRunController.submitRun()  │
│                                      │
│  2. 异步执行任务                      │
│     WorkerRunService.submitAsync()   │
│     ├─ WorkerRunService.executeSync()│
│     │  ├─ 获取执行器                 │
│     │  ├─ ProcessExecutionHelper     │
│     │  │  ├─ 启动进程                │
│     │  │  ├─ RunningTaskRegistry.reg │
│     │  │  ├─ 等待完成/超时           │
│     │  │  └─ 返回 ExecutionResult    │
│     │  └─ 捕获异常                   │
│     └─ ExecutionResult 生成          │
│                                      │
│  3. 回调调度中心                      │
│     SchedulerCallbackClient.report() │
│     ├─ 构建请求体:                   │
│     │  TaskStatusTransitionBody      │
│     │  ├─ fromStatus: RUNNING        │
│     │  ├─ toStatus: SUCCESS/FAILED/  │
│     │  │            TIMEOUT          │
│     │  ├─ triggerSource: WORKER      │
│     │  ├─ reason: 执行成功/超时/失败 │
│     │  └─ exitCode, errorMessage     │
│     └─ HTTP POST 到 Scheduler        │
│        + X-Internal-Token 请求头     │
└──────────────────────────────────────┘
            ↓↓↓
┌─ Scheduler 端 ────────────────────────────┐
│                                             │
│  1. 接收回调请求                            │
│     TaskInstanceController.transitionStatus│
│     ├─ 验证 X-Internal-Token               │
│     └─ 调用 transitionStatus()             │
│                                             │
│  2. 状态验证 & 幂等性检查                   │
│     TaskInstanceServiceImpl.transitionStatus│
│     ├─ 状态格式检查                        │
│     ├─ 转移合法性检查（ALLOWED_TRANSITIONS│
│     ├─ 任务存在性检查                      │
│     ├─ 幂等性处理                          │
│     │  ├─ 重复请求 → 直接返回 ✓            │
│     │  ├─ 迟到的回调（已终态）→ 忽略 ✓    │
│     │  └─ 非法转移 → 返回 CONFLICT          │
│     └─ 重试状态检查                        │
│                                             │
│  3. 乐观锁更新状态                         │
│     ├─ 版本号校验                          │
│     ├─ 如果 toStatus 是终态:               │
│     │  ├─ 设置 endTime                     │
│     │  ├─ 计算 durationMs                  │
│     │  ├─ 设置 exitCode                    │
│     │  ├─ 设置 errorMessage                │
│     │  └─ 更新 version                     │
│     └─ 执行 UPDATE 语句                    │
│                                             │
│  4. 记录状态变化                           │
│     TaskStatusChangeLog.insert()           │
│     ├─ taskInstanceId                      │
│     ├─ fromStatus → toStatus               │
│     ├─ triggerSource (WORKER)              │
│     └─ reason, operatorUserId              │
│                                             │
│  5. 终态后处理                             │
│     if (toStatus ∈ TERMINAL_STATUSES):     │
│     ├─ publishTaskCompletionEvent()        │
│     │  └─ 发布到 Redis Stream (DAG 驱动)   │
│     └─ TaskInstanceTerminalHandler         │
│        .afterTerminal()                    │
│        └─ ResourceSlotService             │
│           .releaseForTaskInstanceSystem()  │
│           └─ 释放资源槽位                   │
│                                             │
│  6. 返回更新后的任务实例                    │
└─────────────────────────────────────────────┘
```

---

## 第四部分：关键交互点总结

### 状态转换终态类型

| Worker 执行结果 | 终态状态 | 触发点 | 备注 |
|---|---|---|---|
| 成功（exitCode=0） | **SUCCESS** | ProcessExecutionHelper L65 | exitCode → 0 |
| 失败（exitCode≠0） | **FAILED** | ProcessExecutionHelper L68 | 包含 stderr 或错误消息 |
| 超时 | **TIMEOUT** | ProcessExecutionHelper L60 | process.waitFor() 返回 false |
| 线程池满 | **CONFIGURATION_ERROR** | WorkerRunService L48 | 特殊错误，不进入终态 |
| 不支持的任务类型 | **CONFIGURATION_ERROR** | WorkerRunService L68 | 特殊错误，不进入终态 |
| 执行异常 | **FAILURE** | WorkerRunService L79 | 捕获异常 |

### 并发安全机制

1. **乐观锁版本号**
   - 每个 TaskInstance 有 version 字段
   - UPDATE 时验证版本号匹配
   - 版本号不匹配 → CONFLICT，调用方重试

2. **幂等性设计**
   - 迟到回调的重复请求自动忽略
   - 已处于终态的任务拒绝非法转移
   - 相同请求返回成功而不是错误

3. **运行任务并发管理（Worker 端）**
   - ConcurrentHashMap 管理运行中的任务
   - cancel() 时安全移除并强制终止进程

### 超时处理

**Worker 端主动超时（Process 超时）：**
- ProcessExecutionHelper L55-60
- process.waitFor(waitSeconds) 超时
- 强制杀死进程 → ExecutionResult.timedOut()
- 立即回调 TIMEOUT

**Scheduler 端被动超时（守护扫描）：**
- TaskTimeoutWatchdog 每 30s 扫描一次
- 检查 RUNNING 任务是否超过 timeoutSeconds
- Worker 可能还未完成时，系统主动转为 TIMEOUT
- 取消 Worker 侧的执行

---

## 第五部分：配置项参考

### application.properties / application.yml

```properties
# Scheduler 端
internal.api.token=your-secure-token-here
scheduler.base-url=http://scheduler:8080
task.executor.timeout-scan-interval-ms=30000

# Worker 端
worker.executor.work-dir=/data/worker
worker.executor.output-max-chars=10485760  # 10MB
```

---

## 相关文件索引

### Worker 模块
| 文件 | 作用 | 关键行 |
|---|---|---|
| SchedulerCallbackClient.java | 回调发送 | L24, L45-70 |
| WorkerRunService.java | 任务执行 & 回调 | L40, L56, L76 |
| ProcessExecutionHelper.java | 进程执行 | L55-68 |
| RunningTaskRegistry.java | 任务并发管理 | L24-29 |
| ProcessRunningTaskHandle.java | 进程终止 | L16 |

### Scheduler 模块
| 文件 | 作用 | 关键行 |
|---|---|---|
| TaskInstanceController.java | 回调入口 | L47 |
| TaskInstanceServiceImpl.java | 状态转换逻辑 | L73-188 |
| TaskInstanceTerminalHandler.java | 终态处理 | L19 |
| TaskTimeoutWatchdog.java | 超时监控 | L26 |
| WorkerRunRequestFactory.java | 回调 URL 构建 | L27 |

---

## 常见问题与追踪路径

**Q: 为什么 Worker 回调后任务仍是 RUNNING？**
- A: 检查 internal.api.token 是否正确传递（X-Internal-Token 请求头）
- 追踪：SchedulerCallbackClient.java L33-34 → TaskInstanceController.java L52

**Q: 如何手动测试回调 API？**
```bash
curl -X POST http://scheduler:8080/api/internal/task-instances/123/status \
  -H "X-Internal-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{
    "fromStatus": "RUNNING",
    "toStatus": "SUCCESS",
    "triggerSource": "WORKER",
    "reason": "测试成功",
    "exitCode": 0
  }'
```

**Q: 资源何时释放？**
- A: 任务转为终态时立即释放
- 追踪：TaskInstanceServiceImpl.java L188 → TaskInstanceTerminalHandler.java L28

**Q: 如何追踪状态变化历史？**
- A: 查询 `task_status_change_log` 表
- 字段：task_instance_id, from_status, to_status, trigger_source, reason, created_at

