# P4-4: 条件分支支持设计稿（与当前实现对齐）

> **文档状态**：已与 `Distributed Lite Scheduler_V1` 当前代码结构校对。原稿中基于「同进程 CountDownLatch 按层阻塞」的执行引擎示例**不再适用**，已替换为与 **Redis Stream 事件驱动** 一致的集成说明。

## 1. 文档目标

在基础 DAG 执行之上增加**运行时分支选择**能力：

- 如何根据前置任务结果（状态、输出、上下文变量）决定下游是否投递执行；
- 如何表达并解析条件（建议 SpEL）；
- 分支未命中时如何保证编排**不被 PENDING 卡死**（通常标记 `SKIPPED`）；
- 与现有 **分层拓扑 + Stream 驱动下一层** 的语义如何衔接。

**价值**：在工作流仍为**有向无环图（静态 DAG）**的前提下，对部分边引入**运行时布尔门控**，路径由执行结果塑形。

---

## 2. 与当前仓库架构的对齐说明（必读）

### 2.1 依赖与拓扑存在哪里？

| 事项 | 实际项目 |
|------|----------|
| 依赖存储 | **`workflow.dag_json`**（JSON），**没有**独立的 `task_dependency` 数据库表。 |
| Java 模型 | `WorkflowDAG`：`tasks` + `dependencies`；边模型为 **`WorkflowDependency`**（`from` / `to` / **`condition`** 可选）。 |
| 拓扑分层 | **`WorkflowExecutionServiceImpl`**：`buildGraph` → Kahn **`topologicalSort`** → **`WorkflowExecutionPlan`**（每层 `TaskLayer` + `TaskExecutionNode`）。当前 **`buildGraph` 不解析 `condition`**，所有依赖边仍参与拓扑，用于保证仍是 DAG 与分层。 |

### 2.2 运行时谁在推进「下一波任务」？

| 组件 | 路径（包名节选） | 职责 |
|------|------------------|------|
| 首轮投递 | `WorkflowExecutorImpl` | 校验实例 `PENDING` → `RUNNING`，**投递第 0 层**仍为 `PENDING` 的节点到 `TaskSubmitService`。 |
| 完成事件 | `TaskCompletionStreamListener` + **`TaskCompletionStreamHandler`** | 消费 Redis Stream 中的任务完成事件，更新 **`WorkflowTaskInstance`**，在满足「**当前层全部终态**」时 **`submitLayerTasks(nextLayer)`** 或收口工作流。 |
| 暂停后恢复 | `WorkflowControlServiceImpl` + `WorkflowExecutor#resumeWorkflowInstanceAsync` | 将仍为 `PENDING` 的最浅层再次投递。 |

**结论**：条件分支的实现应挂在 **「决定是否对本层或下一层某一节点投递 / 或直接 SKIPPED」** 的逻辑上，而不是替换为设计稿早期版本的 **`WorkflowExecutorWithCondition` + `CountDownLatch`** 伪代码。

### 2.3 执行计划快照里有什么？缺什么？

- 实例创建时，`WorkflowInstance.execution_plan` 保存 **`WorkflowExecutionPlan` 序列化 JSON**。
- **当前快照未包含完整的 `dependencies` 列表**，运行时若仅依赖 `execution_plan`，无法还原「指向某 `to` 节点的边及 `condition`」。
- **实现 P4-4 前建议二选一（或同时做）**：
  1. **扩展 `WorkflowExecutionPlan`**：增加 `List<WorkflowDependency> dependencies`（或与之一致结构），在 `doBuildExecutionPlan` 时从 `WorkflowDAG` 拷贝；**推荐**，Stream 处理只读实例快照即可。
  2. 在 Stream 中按 `workflowId` **回表读取 `workflow.dagJson` 再解析**（注意定义变更与实例快照版本一致性问题）。

### 2.4 分层推进的硬约束（条件分支相关）

`TaskCompletionStreamHandler#checkAndSubmitNextLayer` 在**当前层**所有任务均为终态（`SUCCESS` / `FAILED` / `SKIPPED`）后，才会投递 **`currentLayer + 1`**。

因此：

- **互斥分支**上未被选中的节点**不能长期保持 `PENDING`**，否则整层无法结束 → **死锁**。
- 典型策略：在打开下一层（或在首次投递该层前）对「本层每个待决策节点」求值：满足则保持 `PENDING` 并提交调度；不满足则 **`SKIPPED`**（并视需要扣减/保持实例级计数规则与产品一致）。

### 2.5 任务输出与上下文

- **任务侧**：`WorkflowTaskInstance.output` / `exitCode` / `status` 等可作为条件上下文来源；若用 JSON 输出，需**约定**由执行器或任务把结构化结果写入 `output` 字段（或你们统一的结果通道）。
- **实例级 `context`（如 userId、batchId）**：原设计稿中的 `loadWorkflowContext` 依赖「实例上存 JSON」。**当前 `WorkflowInstance` 若无该字段**，需在 **P4-4 实现阶段**增加字段（如 `context_json`），或在 **创建实例 API** 中接受参数并落库；否则 SpEL 中 `context.*` 无法持久可用。

### 2.6 失败策略

- 实例上有 **`failureStrategy`**（如 `stop_on_failure` / `continue_on_failure`）字段。
- **当前 Stream 路径对「是否继续投递下一层」与失败策略的耦合需单独盘点**；P4-4 实现时应与产品一致：**条件分支**与**失败停流**正交，避免重复语义。

---

## 3. 条件分支概念（业务侧）

### 3.1 定义

根据前置任务执行结果（状态、输出、变量）**动态决定是否对下游边「放行」**；不放行则下游对应节点应进入 **`SKIPPED`**（或你们定义的其他终态），而不是删除节点。

### 3.2 与传统 DAG 的关系

- **拓扑排序**仍基于**完整静态 DAG**（含所有可能走的边），保证无环与分层合理。
- **运行时**仅决定某些边上**是否执行** `to` 节点；不执行则 `to` 对应实例行需尽快进入终态，避免阻塞层完成条件。

---

## 4. 条件表达式设计

### 4.1 技术选型：Spring SpEL

- 与 Spring 技术栈一致，便于在服务端求值。
- 需在实现中注意：**安全与超时**（禁用危险调用、限制求值时间），避免用户表达式拖垮消费线程。

### 4.2 语法说明：不要混用 `${...}` 与 SpEL

早期文档示例使用 **`${tasks.xxx}`**，更像配置占位符风格。**Spring SpEL 原生**通常写作：

- 使用 **`#root`**、**`#tasks`** 等，或
- 将 `tasks` / `context` / `system` 注册为 **EvaluationContext 的 variable**，在表达式里写 **`#tasks['data_validation'].output['score'] >= 90`**。

**建议**：在项目内**统一一种写法**并固定文档；若保留 `${...}` 字符串，实现层应**剥离前缀**或**映射为 SpEL**（实现细节，不在此展开）。

### 4.3 表达式上下文（建议模型）

与业务相关的三类数据：

| 变量名 | 含义 |
|--------|------|
| `tasks` | `Map<nodeName, TaskResult>`：已结束任务的状态、退出码、解析后的 `output` Map、耗时等。 |
| `context` | 工作流实例级参数（需落库或可从创建请求注入）。 |
| `system` | 只读系统字段，如 `workflowInstanceId`、`currentTime`。 |

`TaskResult` 建议字段：`status`（与 `TaskInstanceStatus` 存库一致的大写）、`exitCode`、`output`（`Map<String,Object>`）、`durationSeconds`。

---

## 5. 数据模型（与代码一致）

### 5.1 DAG JSON（`workflow.dag_json`）

- **任务节点**使用 **`nodeName`** 作为依赖引用键（见 `WorkflowTask`），**不是**早期示例里的 `name`。
- **依赖**使用 `WorkflowDependency`：`from` / `to` 对应 **`nodeName`**；**`condition` 可选**，空或 null 表示**不额外门控**（仍受拓扑与上游完成约束）。

示例（字段名与项目一致，条件表达式仅为示意，需按你们选定的 SpEL 风格改写）：

```json
{
  "version": "1.0",
  "tasks": [
    {
      "nodeName": "data_validation",
      "taskId": 1001,
      "displayName": "数据验证"
    },
    {
      "nodeName": "auto_import",
      "taskId": 1002,
      "displayName": "自动导入"
    },
    {
      "nodeName": "manual_review",
      "taskId": 1003,
      "displayName": "人工审核"
    }
  ],
  "dependencies": [
    {
      "from": "data_validation",
      "to": "auto_import",
      "condition": "#tasks['data_validation'].output['score'] >= 90"
    },
    {
      "from": "data_validation",
      "to": "manual_review",
      "condition": "#tasks['data_validation'].output['score'] < 90"
    }
  ]
}
```

### 5.2 数据库

- **无需**按旧稿 `ALTER TABLE task_dependency` 修改；依赖在 DAG JSON 中。
- 若增加实例级上下文：在 **`workflow_instance`** 上增加列（如 `context_json`）或在实现文档中单独立项。

### 5.3 执行计划快照扩展（实现项）

在 **`WorkflowExecutionPlan`** 中增加依赖列表（或与 `WorkflowDependency` 等价结构），并在 **`WorkflowExecutionServiceImpl#doBuildExecutionPlan`** 中赋值，使 **`TaskCompletionStreamHandler`** 仅依赖实例快照即可做条件判断，避免运行时再读工作流定义导致版本漂移。

---

## 6. 运行时集成设计（替代原 §5.3 伪代码）

### 6.1 禁止方案（与仓库不符）

- **不要**采用「`WorkflowExecutorWithCondition` 继承 `WorkflowExecutor` + `CountDownLatch` 层内阻塞等待全部任务结束」的主流程；当前主流程为 **异步调度 + Stream 回调**。

### 6.2 推荐锚点

1. **`TaskCompletionStreamHandler`**
   - 在「当前层全部终态」之后、**投递 `nextLayer` 之前或之中**：
     - 对 `nextLayer` 中每个 `WorkflowTaskInstance`，根据 **入边依赖 + `condition`** 决定是否 **提交** 或 **标记 `SKIPPED`**。
   - 对 **同一层内** 因条件晚于其他任务才「可判定」的节点，需与产品约定是否允许；**最小实现**可先支持「条件仅出现在自上一层出发的边上，且该层所有前驱已终态」。

2. **`WorkflowExecutorImpl`（首轮与 `resume`）**
   - 在 **首次投递某层** 时，与 Stream 路径共用一套 **「过滤 + SKIPPED」** 逻辑，避免暂停恢复后出现与 Stream 不一致的行为。

### 6.3 条件求值组件（建议拆分）

| 组件 | 职责 |
|------|------|
| `ConditionEvaluator` | `evaluate(expr, EvaluationContext)`；无表达式或空白 → `true`。 |
| `ConditionContextBuilder` | 给定 `workflowInstanceId`，组装 `tasks` / `context` / `system`。 |

**登记变量**：例如 `evaluationContext.setVariable("tasks", taskResultMap);` 等，与 §4.3 一致。

### 6.4 多前置（Join）与 SKIPPED 语义（需产品拍板）

- 若 `task_d` 依赖 `task_b` 与 `task_c`，其中一条分支因条件未被选中而为 **`SKIPPED`**，`task_d` 是否仍应执行？
- 常见规则：**所有直接前驱必须终态**；`SKIPPED` 是否视为「满足依赖」需明确定义（AND/OR、是否要求至少一条 SUCCESS 等）。
- **未定义前不要盲目实现**，否则与监控、`failed_tasks` 统计会不一致。

### 6.5 与工作流定义的校验

在 **`WorkflowServiceImpl` 创建/更新 DAG** 时，建议增加：

- 每条 `dependencies[].condition` 的 **语法校验**（可选用 SpEL `parseExpression`）；
- **可选**：静态检查引用的 `nodeName`、`output` 路径无法解析时仅告警（弱校验）。

---

## 7. 任务输出标准化（仍建议保留）

为便于 `output` Map 参与条件，建议任务将**结构化结果**打印到约定通道并最终写入 `WorkflowTaskInstance.output`（JSON 字符串）。以下示例仅作说明，与具体执行器对接由调度/执行侧保证。

**Python 示例（节选）**：将结果 JSON 打印到 stdout，由执行器采集写入 `output`。

**Shell 示例（节选）**：`echo '{"accuracy":0.96}'` 等。

（原稿 §5.4 完整示例可继续作为脚本侧参考，此处不重复占用篇幅。）

---

## 8. API 与前端（与当前 Controller 对齐）

### 8.1 定义带条件的工作流

实际创建请求体为 **`WorkflowCreateRequest`**，其中 **`dagJson` 为字符串**（内部是 `WorkflowDAG` JSON），不是嵌套对象字段。

```http
POST /api/workflow
Content-Type: application/json
```

```json
{
  "projectId": 100,
  "workflowName": "数据质量检查工作流",
  "dagJson": "{\"version\":\"1.0\",\"tasks\":[...],\"dependencies\":[...]}"
}
```

### 8.2 运行实例

```http
POST /api/workflow/instance/execute
```

请求体为 **`WorkflowInstanceCreateRequest`**（`workflowId`、`failureStrategy`、`executeImmediately` 等）。若引入实例级 `context`，在此请求中扩展字段并与 §2.5 存储方案一致。

### 8.3 条件校验 / 调试 API

原稿中的 `POST /api/workflow/condition/validate`、`/evaluate` **尚未在仓库中作为既定接口**；可作为 **P4-4 实施时的可选配套**，便于运营调试 SpEL。

---

## 9. 高级场景（保留思路，实现分阶段）

### 9.1 多路分支（Switch）

多条边自同一 `from` 指向不同 `to`，各带**互斥**条件；需保证**至少一条**在运行时可命中或定义**默认边**，否则应显式失败/告警。

### 9.2 AND / OR 组合

在**单条边**的 `condition` 内用 SpEL 组合；注意 `tasks` 中尚未运行的节点**不在 `TaskResult` 中**，避免 NPE。

### 9.3 默认分支（Else）

可用「显式第二条边 + 宽条件」模拟；**不推荐**依赖「无 else 则自动执行」，避免语义歧义。

### 9.4 循环 / `LoopConfig`

原稿中 **`WorkflowTask.loopConfig` 在当前 `WorkflowTask` 实体中不存在**，属于**更远期**能力；与 P4-4 **条件边**解耦，单独立项。

---

## 10. 监控与可观测

- 条件求值：建议 **结构化日志**（`instanceId`、`nodeName`、`expression`、`result`、耗时）；避免在日志中打印过大 `output`。
- **分支可视化**：可基于 `WorkflowTaskInstance` 的 `SKIPPED` / `SUCCESS` 与实例 `execution_plan` 展示「理论边」与「实际走过节点」。（`WorkflowInstanceMonitorController` 已有进度/时间线类接口，可迭代展示条件原因字段——需实现时扩展 VO。）

---

## 11. 测试建议

| 类型 | 要点 |
|------|------|
| 单元测试 | `ConditionEvaluator` + 边界上下文（缺 key、output 非 JSON、表达式异常）。 |
| 集成测试 | **异步**：`WorkflowExecutorImpl`/`Stream` 全链路，`await`/轮询直至实例终态；断言互斥两支一支 `SUCCESS` 一支 `SKIPPED`。 |
| 回归 | `PAUSED`/`CANCELLED` 不打下一层、`resumeWorkflowInstanceAsync` 与条件过滤一致。 |

原稿中单测里 `${tasks.task_a...}` 示例需在项目确定 SpEL 风格后改写。

---

## 12. 性能与FAQ（修订）

### 12.1 性能

- 可对**解析后的 Expression**做缓存（key 为表达式字符串）；**上下文不可缓存**。
- Stream 消费线程上求值应保持轻量；重计算应挪到异步任务或由任务本身产出结果字段。

### 12.2 FAQ

**Q：条件分支影响拓扑排序吗？**  
**A：** 拓扑仍用**全集依赖边**。条件只影响**运行时是否执行 `to`**；不负责从拓扑里删边。

**Q：为什么会出现死锁？**  
**A：** 某节点应跳过但仍为 **`PENDING`**，导致该层永远不满足「全终态」。必须通过 **SKIPPED**（或等价终态）解决。

**Q：原设计稿里的 `task_dependency` 表？**  
**A：** 本仓库**不使用**该表；以 **`workflow.dag_json`** 为准。

**Q：`executeWorkflowInstance` 会等整图跑完吗？**  
**A：** **不会。** 当前设计为异步投递 + Redis Stream 驱动后续层；收口在 `TaskCompletionStreamHandler`（及失败路径）等处。

---

## 13. 后续优化方向（保留方向性）

- 注册 SpEL **自定义函数**（如租户日历、配额判断）；
- 条件模板与可视化编辑器；
- 分支覆盖率统计（基于实例与 `WorkflowTaskInstance` 聚合）。

---

## 14. 总结

| 维度 | 说明 |
|------|------|
| 模型 | `WorkflowDependency.condition` **已在代码中存在**；DAG 存 **`dag_json`**。 |
| 缺口 | **`WorkflowExecutionPlan` 需携带依赖**，或运行时回读 DAG；实例 **context** 若要用需落库。 |
| 集成 | 以 **`TaskCompletionStreamHandler`**（及 **`WorkflowExecutorImpl` 首投/恢复**）为唯一下推点；**禁止**沿用 CountDownLatch 主流程伪代码。 |
| 风险 | **Join + SKIPPED 语义**、**失败策略与继续投递**、**SpEL 安全**。 |

按上述与仓库对齐后，P4-4 可从「最小互斥二分支 + 计划快照带依赖 + Stream 内 SKIPPED」迭代上线，再扩展多路与 Join 规则。

---

## 附录 A：任务输出 JSON 示例（脚本侧）

供条件 `#tasks['data_validation'].output['score']` 等解析使用；最终以执行器写入 `WorkflowTaskInstance.output` 的结果为准。

**Python（节选）**

```python
import json
import sys

result = {
    "validRecords": 9500,
    "invalidRecords": 500,
    "score": 95.0
}
print(json.dumps(result))
sys.exit(0)
```

**Shell（节选）**

```bash
echo "{\"score\": 95}"
exit 0
```
