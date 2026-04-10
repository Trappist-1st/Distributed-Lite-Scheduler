# 任务状态机落地设计稿（P1-3）

## 1. 文档目标

本文档用于将 `P1-3: 任务状态机` 从任务清单转换为可执行实施方案，覆盖：

- 状态模型定义（执行态归属与语义）
- 状态转换规则（合法矩阵 + 非法拦截）
- 服务层与接口层改造点
- 并发与幂等处理策略
- 事件与日志落盘策略
- 测试计划、验收标准、上线建议

目标是让任务执行生命周期可控、可观测、可恢复，并为后续调度器（Phase 3）提供稳定状态基础。

---

## 2. 范围与非范围

### 2.1 本阶段范围（必须完成）

1. 定义统一状态枚举（`PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT`）
2. 实现状态转换校验器（`canTransition(from, to)`）
3. 提供状态转换服务入口（集中校验 + 更新）
4. 非法转换抛出业务异常并返回明确错误码
5. 状态变更审计日志（至少记录前态、后态、触发来源、时间）
6. 单元测试覆盖状态矩阵 + 关键并发场景

### 2.2 非本阶段范围（后续阶段处理）

1. 完整调度策略（FIFO、优先级、资源感知）
2. 跨节点分布式协调（Leader 选举、分布式锁）
3. 执行器协议细节（心跳、拉取日志、断点重试）
4. 工作流级 DAG 依赖状态传播

---

## 3. 状态归属设计（核心决策）

## 3.1 执行状态应归属 `task_instance`

建议执行态统一放在 `task_instance.status`，而不是 `task.status`：

- `task` 表示“任务定义”（模板/配置），其状态更偏“启用/禁用”
- `task_instance` 表示“任务一次执行”，天然需要 `PENDING -> RUNNING -> ...` 生命周期

这能避免“定义状态”和“运行状态”混淆，也更契合后续调度与重试场景。

## 3.2 当前项目映射建议

- `task.status` 继续保留为定义状态（例如：`0=DISABLED, 1=ENABLED`）
- 新增或强化 `task_instance.status` 的状态机流转控制
- 所有执行相关查询（运行中、失败重试、耗时统计）基于 `task_instance`

---

## 4. 状态定义与语义

## 4.1 枚举建议

```java
public enum TaskExecutionStatus {
    PENDING,   // 已创建，待调度
    RUNNING,   // 已分配执行器，执行中
    SUCCESS,   // 执行成功（终态）
    FAILED,    // 执行失败（终态）
    CANCELLED, // 被用户或系统取消（终态）
    TIMEOUT    // 执行超时（终态）
}
```

## 4.2 状态语义

- `PENDING`：实例已入队但尚未开始执行
- `RUNNING`：实例被执行器接管并开始处理
- `SUCCESS`：正常完成，退出码满足成功判定
- `FAILED`：执行器返回失败或业务异常终止
- `CANCELLED`：人工取消或系统策略取消
- `TIMEOUT`：超过超时阈值被系统收敛为终态

---

## 5. 状态转换规则

## 5.1 合法转换矩阵

| From \\ To | PENDING | RUNNING | SUCCESS | FAILED | CANCELLED | TIMEOUT |
|---|---:|---:|---:|---:|---:|---:|
| PENDING   | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ |
| RUNNING   | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| SUCCESS   | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| FAILED    | ✅(重试场景，创建新实例更优) | ❌ | ❌ | ❌ | ❌ | ❌ |
| CANCELLED | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| TIMEOUT   | ✅(重试场景，创建新实例更优) | ❌ | ❌ | ❌ | ❌ | ❌ |

> 说明：若采用“每次重试创建新实例”策略，`FAILED/TIMEOUT -> PENDING` 可关闭；若采用“同实例重试”，可保留该转移并增加 `retry_count` 防护。

## 5.2 转换原则

1. 终态不可直接跳回运行态（除重试特例）
2. 不允许跨越关键中间态（如 `PENDING -> SUCCESS`）
3. 状态转换必须由统一服务入口执行，禁止绕过
4. 每次转换必须记录触发来源（`SCHEDULER/WORKER/API/SYSTEM`）

---

## 6. 服务层落地方案

## 6.1 推荐组件拆分

1. `TaskExecutionStatus`（枚举）
2. `TaskStatusTransitionRule`（规则定义）
3. `TaskStatusMachine`（转换判定器）
4. `TaskInstanceStatusService`（唯一更新入口）
5. `TaskStatusChangeLogService`（审计日志）

## 6.2 关键方法草案

```java
boolean canTransition(TaskExecutionStatus from, TaskExecutionStatus to);

Result<TaskInstance> transitionStatus(
    Long taskInstanceId,
    TaskExecutionStatus expectedFrom,
    TaskExecutionStatus targetTo,
    String triggerSource,
    String reason
);
```

## 6.3 更新约束

- 必须先读当前状态再校验
- 写入时使用乐观锁（`version`）避免并发覆盖
- `update ... where id=? and status=? and version=?` 保证原子性
- 更新失败返回可重试错误（并记录冲突日志）

---

## 7. API 与交互建议

## 7.1 内部状态变更接口（建议）

### `POST /api/internal/task-instances/{id}/status`

- 入参：`fromStatus`, `toStatus`, `triggerSource`, `reason`
- 行为：调用状态机服务统一校验与落盘
- 返回：变更后的实例快照 + 版本号

> 说明：对外部用户 API 建议保持受限，仅允许“取消”等安全动作；`RUNNING/SUCCESS/FAILED` 更适合作为内部执行链路调用。

## 7.2 常见动作映射

- 调度器选中任务：`PENDING -> RUNNING`
- Worker 执行成功：`RUNNING -> SUCCESS`
- Worker 执行失败：`RUNNING -> FAILED`
- 用户主动取消：`PENDING/RUNNING -> CANCELLED`
- 超时守护线程：`RUNNING -> TIMEOUT`

---

## 8. 并发、幂等与一致性

## 8.1 并发风险

1. 调度器与超时线程同时改同一实例
2. Worker 重复上报完成结果
3. 网络抖动导致回调重放

## 8.2 防护策略

1. 乐观锁 + 条件更新（首选）
2. 回调携带幂等键（`instanceCode + eventType + seq`）
3. 状态推进只允许“单向前进”且校验前态
4. 对重复成功回调可做“幂等成功返回”，不重复写

---

## 9. 状态变更审计与可观测性

## 9.1 审计字段建议

`task_status_change_log`（可新表，或先写应用日志）字段建议：

- `task_instance_id`
- `from_status`
- `to_status`
- `trigger_source`
- `operator_user_id`（可空）
- `reason`
- `created_at`

## 9.2 观测指标建议

- 状态流转成功率
- 非法流转拦截次数
- `RUNNING -> TIMEOUT` 比例
- 终态分布（`SUCCESS/FAILED/CANCELLED/TIMEOUT`）

---

## 10. 测试计划

## 10.1 单元测试（必须）

1. 合法转换全覆盖（矩阵逐项断言）
2. 非法转换抛异常并返回预期错误码
3. 终态不可继续流转
4. 重试策略开关（是否允许 `FAILED/TIMEOUT -> PENDING`）

## 10.2 集成测试（建议）

1. 调度器模拟：`PENDING -> RUNNING`
2. Worker 成功/失败回调：`RUNNING -> SUCCESS/FAILED`
3. 超时任务收敛：`RUNNING -> TIMEOUT`
4. 并发冲突：双线程同时更新同一实例，仅一方成功

## 10.3 回归测试关注点

1. 任务列表接口是否正确返回实例状态筛选
2. 原有任务定义 CRUD 行为不受影响
3. 租户边界不被状态接口绕过

---

## 11. 分阶段实施清单（建议 3 天）

## Day 1：模型与规则

- [ ] 定义 `TaskExecutionStatus` 枚举
- [ ] 实现 `canTransition` + 规则矩阵
- [ ] 增加业务异常与错误码（非法转换）

## Day 2：服务与持久化

- [ ] 实现 `TaskInstanceStatusService.transitionStatus`
- [ ] 加入乐观锁条件更新
- [ ] 增加状态变更日志记录

## Day 3：接口与测试

- [ ] 增加内部状态变更接口（或服务调用入口）
- [ ] 完成单元测试 + 集成测试
- [ ] 补充文档与示例调用

---

## 12. 验收标准（Definition of Done）

1. 任意状态变更均经过统一状态机校验
2. 非法转换 100% 被拒绝，并返回可定位错误
3. 并发更新无“后写覆盖前写”问题
4. 状态审计可追溯（至少可查前态/后态/触发来源）
5. 单元测试覆盖核心矩阵，关键集成链路通过

---

## 13. 风险与缓解

## 13.1 风险

1. 业务方绕过状态机直接改库
2. 重试策略不一致导致状态语义混乱
3. 高并发下状态更新冲突频发

## 13.2 缓解措施

1. 下沉统一服务入口并限制 Mapper 直写
2. 在文档中固定“重试模型”（新实例或同实例）
3. 对冲突场景做可重试机制 + 指标告警

---

## 14. 任务状态流转的作用（为什么必须做）

任务状态机不是“给状态字段加几个值”，而是执行系统的控制面。它的作用主要是：

1. **保证执行正确性**：防止出现 `PENDING -> SUCCESS` 这类非法跳变，避免业务数据失真。  
2. **保障并发一致性**：多角色（调度器、Worker、超时器、用户）同时操作时，有统一规则防冲突。  
3. **支撑故障恢复与重试**：只有状态边界清晰，才能可靠实现重试、补偿、超时回收。  
4. **提升可观测性**：从“发生了什么”变成“为什么发生、谁触发的、是否符合预期”。  
5. **解耦后续能力**：优先级调度、DAG 编排、SLA 告警都依赖可靠的生命周期状态。  

一句话：**状态机让任务执行从“能跑”升级为“可控、可解释、可演进”**。

