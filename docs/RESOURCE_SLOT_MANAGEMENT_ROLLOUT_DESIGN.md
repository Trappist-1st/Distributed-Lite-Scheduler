# 资源槽位管理落地设计稿（P2-2）

## 1. 文档目标

本文档将 `P2-2: 资源槽位管理` 拆解为可执行方案，覆盖：

- CPU/GPU/Memory 槽位抽象模型
- 资源预留与释放接口
- 资源使用记录（审计与统计基础）
- 并发安全与超分防护策略
- 测试与验收标准

目标是为 Phase 3 调度器提供“可分配、可回收、可追踪”的资源控制面。

---

## 2. 范围与非范围

### 2.1 本阶段范围（必须完成）

1. 资源槽位抽象（节点维度）
2. 资源预留接口（Reserve）
3. 资源释放接口（Release）
4. 资源使用记录（Usage Log）
5. 并发抢占冲突控制

### 2.2 非本阶段范围（后续阶段处理）

1. 复杂调度算法（Best Fit / 多级队列）
2. 异构资源亲和调度策略
3. 全局资源弹性扩缩容

---

## 3. 数据模型设计

`TASK_BREAKDOWN` 提到核心表：`resource_slot`, `resource_usage`。建议如下：

## 3.1 `resource_slot`（节点资源快照）

建议字段：

- `id`
- `node_id`
- `resource_type`（CPU/GPU/MEMORY）
- `total`
- `available`
- `reserved`
- `version`
- `updated_at`

建议唯一键：

- `uk_node_resource_type(node_id, resource_type)`

说明：

- `available` 表示可立即分配资源
- `reserved` 表示已预留未释放资源（可选，也可由 `total-available` 计算）

## 3.2 `resource_usage`（资源使用流水）

建议字段：

- `id`
- `tenant_id`
- `task_instance_id`
- `node_id`
- `cpu_used`
- `memory_mb_used`
- `gpu_used`
- `status`（RESERVED/RUNNING/RELEASED/FAILED）
- `reason`（可选）
- `created_at`
- `released_at`

建议索引：

- `idx_tenant_status(tenant_id, status)`
- `idx_task_instance(task_instance_id)`
- `idx_node_status(node_id, status)`

---

## 4. API 设计

统一前缀建议：`/api/resource`

### 4.1 `POST /api/resource/reserve`

作用：为任务实例预留资源

入参建议：

- `tenantId`
- `taskInstanceId`
- `resourceRequirement`（cpu/memoryMb/gpu）
- `candidateNodeIds`（可选，调度器给定）

返回建议：

- `success`
- `nodeId`（分配节点）
- `reservationId` 或 `usageId`
- 分配明细（cpu/memoryMb/gpu）

处理流程建议：

1. 解析资源需求并做基础校验
2. 从候选节点中筛选可满足节点
3. 条件更新 `resource_slot.available`（原子扣减）
4. 写入 `resource_usage` 状态为 `RESERVED` 或 `RUNNING`
5. 任一步失败回滚

### 4.2 `POST /api/resource/release`

作用：释放任务占用资源

入参建议：

- `taskInstanceId` 或 `usageId`
- `reason`（SUCCESS/FAILED/CANCELLED/TIMEOUT）

处理流程：

1. 查询对应活跃 usage（未释放）
2. 回补 `resource_slot.available`
3. 更新 usage 为 `RELEASED`
4. 幂等：重复释放返回成功但不重复回补

### 4.3 `GET /api/resource/usage`

作用：查询资源使用记录

查询参数建议：

- `tenantId`
- `status`
- `nodeId`
- `page`, `size`

---

## 5. 核心分配逻辑（最小可行）

```java
for (node in candidates) {
  if (node.cpu >= req.cpu && node.mem >= req.mem && node.gpu >= req.gpu) {
    // 原子扣减 available
    // 成功则落 usage 并返回
  }
}
return NO_CAPACITY;
```

建议先实现“首个可用节点”策略，再升级为 Best Fit。

---

## 6. 并发与一致性

1. 扣减/回补必须原子化（SQL 条件更新 + 乐观锁）
2. 防止 `available` 变负数（`available >= need` 条件）
3. 预留与释放必须事务包裹
4. 重复释放幂等处理（通过 usage 状态机控制）
5. 服务异常时靠 usage 状态回溯补偿

---

## 7. 与任务状态机关联

建议绑定关系：

- `PENDING -> RUNNING` 前触发 `reserve`
- `RUNNING -> SUCCESS/FAILED/CANCELLED/TIMEOUT` 后触发 `release`

这样能保证“任务状态”和“资源占用”强一致，避免资源泄漏。

---

## 8. 测试计划

## 8.1 单元测试

1. 资源足够时预留成功
2. 资源不足时拒绝分配
3. 重复释放幂等成功
4. 释放后 available 回补正确

## 8.2 集成测试

1. 高并发抢占同一节点不超分
2. 任务终态触发释放
3. 异常中断后补偿可恢复
4. usage 记录可用于统计

---

## 9. 验收标准（Definition of Done）

1. 可完成资源预留与释放完整闭环
2. 并发场景无超分、无负数 available
3. usage 记录能追踪谁在何时占用与释放资源
4. 可为 P2-3 提供“租户当前使用量”数据输入

---

## 10. 实施顺序（建议 1.5~2 天）

Day 1：

- [ ] 建表（`resource_slot/resource_usage`）与实体
- [ ] reserve/release 服务与接口

Day 2：

- [ ] usage 查询接口
- [ ] 并发与幂等测试
- [ ] 状态机联调

