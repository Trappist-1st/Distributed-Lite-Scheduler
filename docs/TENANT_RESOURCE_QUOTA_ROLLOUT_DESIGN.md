# 租户资源配额落地设计稿（P2-3）

## 1. 文档目标

本文档将 `P2-3: 租户资源配额` 转化为可执行方案，覆盖：

- 配额模型与管理接口
- 任务运行前配额检查
- 超额拒绝与降级策略
- 与 `resource_usage` 的联动统计
- 测试与验收标准

目标是建立多租户公平、可治理的资源边界，为调度引擎提供稳定约束层。

---

## 2. 范围与非范围

### 2.1 本阶段范围（必须完成）

1. 配额设置与查询接口
2. 预留资源前的配额校验
3. 超额请求拒绝策略
4. 租户资源使用量统计逻辑

### 2.2 非本阶段范围（后续阶段处理）

1. 配额自动扩缩容
2. 配额借用/突发信用额度
3. 跨租户成本结算

---

## 3. 数据模型与口径

核心表：`resource_quota`（已存在）

关键字段：

- `max_cpu/max_memory_mb/max_gpu`
- `max_running_tasks/max_pending_tasks`
- `used_cpu/used_memory_mb/used_gpu`
- `running_tasks`
- `version`

口径建议：

1. `used_*` 作为快速读缓存值（高频读取）
2. 同时可通过 `resource_usage` 实时聚合做对账与纠偏
3. 发生不一致时，以 usage 聚合结果修正 quota 快照

---

## 4. API 设计

统一前缀建议：`/api/resource/quota`

### 4.1 `GET /api/resource/quota/{tenantId}`

作用：查询租户当前配额与使用情况

返回建议：

- 配额上限（max）
- 当前使用（used/running）
- 使用率（可选）

### 4.2 `PUT /api/resource/quota/{tenantId}`

作用：更新租户配额

权限建议：

- 平台管理员或租户 `OWNER`

入参建议：

- `maxCpu`
- `maxMemoryMb`
- `maxGpu`
- `maxRunningTasks`
- `maxPendingTasks`

约束：

1. 新配额不能小于当前已使用量（避免“立即违约”）
2. 变更应记录审计日志

### 4.3 `POST /api/resource/quota/{tenantId}/check`

作用：预检查某次资源申请是否超额（可供调度器调用）

入参：

- `requestCpu`
- `requestMemoryMb`
- `requestGpu`

返回：

- `allowed`（true/false）
- `rejectReason`（如 `CPU_QUOTA_EXCEEDED`）

---

## 5. 配额检查逻辑

## 5.1 检查时机

建议在 `reserve` 之前执行，顺序如下：

1. 节点资源可满足（P2-2）
2. 租户配额可满足（P2-3）
3. 才真正落预留

## 5.2 检查规则

示例判断：

- `used_cpu + request_cpu <= max_cpu`
- `used_memory_mb + request_memory_mb <= max_memory_mb`
- `used_gpu + request_gpu <= max_gpu`
- `running_tasks + 1 <= max_running_tasks`

---

## 6. 关键 SQL 模板

## 6.1 基于 usage 的实时聚合（校验/对账）

```sql
SELECT
  COALESCE(SUM(cpu_used), 0) AS total_cpu_used,
  COALESCE(SUM(memory_mb_used), 0) AS total_memory_used,
  COALESCE(SUM(gpu_used), 0) AS total_gpu_used
FROM resource_usage
WHERE tenant_id = :tenantId
  AND status IN ('RESERVED', 'RUNNING');
```

## 6.2 配额行并发更新（乐观锁）

```sql
UPDATE resource_quota
SET used_cpu = used_cpu + :cpu,
    used_memory_mb = used_memory_mb + :mem,
    used_gpu = used_gpu + :gpu,
    running_tasks = running_tasks + :deltaTask,
    version = version + 1
WHERE tenant_id = :tenantId
  AND version = :version
  AND used_cpu + :cpu <= max_cpu
  AND used_memory_mb + :mem <= max_memory_mb
  AND used_gpu + :gpu <= max_gpu
  AND running_tasks + :deltaTask <= max_running_tasks;
```

---

## 7. 超额拒绝策略

建议先实现硬拒绝（最简单且安全）：

1. 若任一维度超额，直接拒绝本次预留
2. 返回明确拒绝原因（CPU/MEMORY/GPU/RUNNING_TASKS）
3. 不做部分分配，不做自动降配（后续再扩展）

可选增强：

- 允许低优先级任务排队重试
- 支持按租户优先级做弹性借用

---

## 8. 与 P2-2、P3 的衔接

1. P2-2 负责“节点能否给得出资源”
2. P2-3 负责“租户是否有权继续使用资源”
3. Phase 3 调度器在分配链路中必须同时满足两者

一句话：**节点约束解决“供给”，配额约束解决“公平”**。

---

## 9. 测试计划

## 9.1 单元测试

1. 正常配额内请求通过
2. 超出 CPU/MEMORY/GPU 任一维度拒绝
3. 并发请求下不突破上限
4. 释放后配额使用量正确回退

## 9.2 集成测试

1. reserve 前置配额检查生效
2. 配额更新后新请求立即按新规则生效
3. usage 聚合与 quota 快照一致性校验

---

## 10. 验收标准（Definition of Done）

1. 能按租户准确设置与读取配额
2. 资源预留前可完成配额校验
3. 超额请求被明确拒绝，原因可解释
4. 并发情况下不突破配额上限
5. 为 Phase 3 调度提供稳定公平约束

---

## 11. 实施顺序（建议 1~1.5 天）

Day 1：

- [ ] quota 查询/更新接口
- [ ] 配额检查服务

Day 2（半天）：

- [ ] 与 reserve 联调
- [ ] 压测与并发边界验证
- [ ] 文档与告警指标补充

