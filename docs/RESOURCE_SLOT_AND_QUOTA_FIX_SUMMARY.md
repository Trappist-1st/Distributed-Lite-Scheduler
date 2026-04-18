# 资源槽位与租户配额修复总结（2026）

本文档说明针对 `ResourceSlotServiceImpl` 及相关组件的一次加固改动，对应评审中的 P0/P1/P2 项；**未**单独落地「补偿服务」「独立审计表」等重型方案，仅在文档中标注后续可选方向。

---

## P0：配额时间窗口与事务提交语义

### 原问题

- `evaluateReserveFeasibility` 仅为内存预检，与后续 `tryConsumeForReserve` 之间仍存在竞态。
- `tryConsumeForReserve` 原放在「槽位扣减 + usage 插入」之后；部分失败路径通过 `return Result.failure` 结束方法时，**Spring `@Transactional` 仍会提交已执行的配额 UPDATE**，与「失败应不占配额」的预期不一致。

### 修复

1. **删除** `reserve` 中对 `evaluateReserveFeasibility` 的调用（配额仍以 `ResourceQuotaController` 的 `/check` 接口供调度预演）。
2. **在候选节点循环之前**调用一次 `tryConsumeForReserve`（单条条件 UPDATE，原子占配额）。
3. 凡在已 `consume` 之后、**未成功返回**的路径：
   - `insert != 1`：`undoDecrementAll` + `releaseForReserve`（对称回补配额）；
   - `DataIntegrityViolationException`：同上；
   - 所有候选节点均不可用：`rollbackQuotaConsumed`（内部即 `releaseForReserve`）。
4. `task_instance` 绑定更新 `tu != 1`：**仅抛 `IllegalStateException`**，依赖事务回滚撤销本事务内的 quota/slots/usage，**不再**先 `undoDecrementAll`（避免与回滚叠加导致槽位双重回补）。

---

## P0：FAILED（等）流水无法释放 / 重复预留漏洞

### 原问题

- `release` / `findActiveUsage` 仅识别 `RESERVED`/`RUNNING`，`FAILED` 等状态无法走释放回补。
- 重复预留只统计 `RESERVED`/`RUNNING`，存在「终态流水未释放仍可再 reserve」的漏洞。

### 修复

1. 新增 `constant/ResourceUsageStatuses.java` 集中定义状态及集合：
   - **`RELEASABLE_STATUSES`**：`RESERVED`、`RUNNING`、`FAILED`、`CANCELLED`（释放条件与 UPDATE `IN` 一致）。
   - **`BLOCK_NEW_RESERVE_UNTIL_RELEASED`**：与上相同集合语义——**非 `RELEASED` 的占用类流水均阻止再次 reserve**，必须先 `release`。
2. `release` 的 UPDATE 条件改为 `RELEASABLE_STATUSES`。
3. `nodeId == null` 时记录 WARN，跳过槽位回补，仍执行配额 `releaseForReserve`（避免配额悬挂；槽位依赖人工对账）。

---

## P1：参数与日志

- `reserve` / `release` / `listUsage` 入口增加 **`request == null`**、`tenantId` 上下文等防御性判断。
- `buildReleaseReason` 对 **`reason` 为空** 时回退为 `"UNKNOWN"`，避免 NPE。
- 使用 **SLF4J**：预留成功、释放成功、冲突/槽位回补失败等关键路径打 **INFO/WARN/ERROR**。

---

## P1：候选节点 N+1 查询

- 对「显式候选 `candidateNodeIds`」路径：使用 **`IN` 一次查询** `loadOnlineCandidatesById`，在内存中按 id 取 `ResourceNode`。
- 「全量 ONLINE 节点」路径：`resolveCandidateNodes` **只查一次** `resource_node`，直接构造 `id → ResourceNode` 映射，避免原先「先查 id 列表再 IN 一遍」的二次查询。

---

## P2：槽位初始化原子性

- 在 `ResourceSlotMapper` 增加 **`insertSlotsIfAbsent`**：单条 `INSERT IGNORE` 写入 CPU/MEMORY/GPU 三行，利用 `uk_node_resource_type` 去重，缩小并发窗口。
- `ensureSlotsInitialized` 改为仅调用该方法；删除逐行 `select + insert` 的 `upsertSlotRow` 实现。

---

## 未实现（酌情 / 后续）

| 项 | 说明 |
|----|------|
| **ResourceCompensationService** | 需定时任务、告警与对账规则；本次仅通过日志 + 显式 `releaseForReserve` 降低悬挂风险。 |
| **ResourceUsageMapper 专用补偿查询** | 可与对账任务一并引入。 |
| **IllegalStateException 统一映射** | 绑定失败等仍走默认 500；若需统一为 `Result`，可再加 `@ExceptionHandler`。 |

---

## 涉及文件清单

| 文件 | 变更要点 |
|------|-----------|
| `ResourceSlotServiceImpl.java` | 配额顺序、失败回补、状态集合、批量查节点、日志、空校验 |
| `ResourceSlotMapper.java` | `insertSlotsIfAbsent` |
| `ResourceUsageStatuses.java` | 新建状态常量类 |
| `ListResourceUsageRequest.java` | 筛选状态增加 `CANCELLED` |

---

## 回归建议

1. 同租户并发 `reserve`：配额不应被突破；失败请求应可重试。
2. `FAILED` 流水：`release` 后槽位与 `resource_quota` 应回补，`reserve` 同一 `task_instance_id` 可再次成功。
3. 候选节点较多时：确认仅 **1～2 次** 节点相关查询（批量 IN + 或全表 ONLINE 列表）。
