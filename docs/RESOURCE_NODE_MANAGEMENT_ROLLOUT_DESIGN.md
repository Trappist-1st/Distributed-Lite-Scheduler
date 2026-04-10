# 资源节点管理落地设计稿（P2-1）

## 1. 文档目标

本文档将 `P2-1: 资源节点管理` 拆解为可直接开发与联调的实施方案，覆盖：

- Worker 节点注册与去重
- 节点心跳上报与健康状态更新
- 节点列表查询与筛选
- 超时自动下线（自动注销）
- 异常与幂等处理、测试与验收

目标是为 `P2-2 资源槽位管理` 提供稳定、可信的节点池基础。

---

## 2. 范围与非范围

### 2.1 本阶段范围（必须完成）

1. `POST /api/resource/register`：节点注册
2. `POST /api/resource/heartbeat`：心跳上报
3. `GET /api/resource/nodes`：节点状态查询
4. 超时无心跳节点自动切换为 `OFFLINE`

### 2.2 非本阶段范围（后续阶段处理）

1. 资源预留/释放（P2-2）
2. 调度决策（Phase 3）
3. 节点灰度与复杂流量治理（后续增强）

---

## 3. 数据模型与状态定义

当前核心表：`resource_node`（已存在）

关键字段建议语义：

- `node_host + node_port`：节点唯一标识（唯一索引）
- `status`：`ONLINE / OFFLINE / MAINTENANCE`
- `last_heartbeat_time`：最近一次心跳时间
- `available_cpu/available_memory_mb/available_gpu`：当前可用资源快照
- `version`：乐观锁字段，用于并发更新防覆盖

状态语义：

- `ONLINE`：可被调度器纳入候选节点池
- `OFFLINE`：不可调度，通常由超时检测或主动下线触发
- `MAINTENANCE`：人工维护态，不参与调度但保留节点信息

---

## 4. API 设计

统一前缀建议：`/api/resource`

### 4.1 `POST /api/resource/register`

作用：Worker 节点注册（或重注册）

入参建议：

- `nodeName`
- `nodeHost`
- `nodePort`
- `nodeType`（CPU/GPU/MIXED）
- `totalCpu`, `totalMemoryMb`, `totalGpu`
- `gpuModel`（可选）
- `labels`（可选 JSON）

处理规则：

1. `node_host + node_port` 已存在：
  - 更新静态信息与容量信息
  - 强制设置 `status=ONLINE`
  - 刷新 `last_heartbeat_time`
2. 不存在：
  - 新建节点，初始 `available=*total`
  - `status=ONLINE`
3. 返回节点详情（含 `id/status/lastHeartbeatTime`）

### 4.2 `POST /api/resource/heartbeat`

作用：节点心跳上报

入参建议：

- `nodeHost`
- `nodePort`
- `availableCpu`
- `availableMemoryMb`
- `availableGpu`
- `status`（可选，默认 `ONLINE`）

处理规则：

1. 找不到节点：返回 `NOT_FOUND` 或按策略自动注册（建议先返回 `NOT_FOUND`，更可控）
2. 找到节点：
  - 刷新 `last_heartbeat_time=now()`
  - 更新可用资源快照
  - 更新状态（若传入）
3. 若节点原为 `OFFLINE` 且心跳恢复，允许自动拉回 `ONLINE`

### 4.3 `GET /api/resource/nodes`

作用：查询节点列表（供控制台与调度器观察）

查询参数建议：

- `status`（可选）
- `nodeType`（可选）
- `keyword`（按 `nodeName/nodeHost`）
- `page`, `size`

返回建议：

- 分页节点列表
- 可选统计（`onlineCount/offlineCount/maintenanceCount`）

---

## 5. 健康检查与自动下线

## 5.1 超时策略

建议参数：

- `heartbeatTimeoutSeconds`（默认 30s 或 60s）
- 判定条件：`now - last_heartbeat_time > timeout`

## 5.2 自动下线流程

触发方式：

1. 定时任务扫描（推荐）
2. 或由调度器拉取前实时过滤（补充）

流程：

1. 扫描 `status=ONLINE` 且超时节点
2. 批量更新为 `OFFLINE`
3. 记录系统日志（节点ID、上次心跳、下线时间）

---

## 6. 并发与幂等策略

1. 注册接口按 `node_host+node_port` 做 upsert 语义，避免重复节点
2. 心跳更新使用乐观锁或条件更新，避免并发覆盖
3. 对同一节点的重复心跳请求返回幂等成功
4. 自动下线与心跳恢复并发时，以“最后写入时间”为准

---

## 7. 异常与错误码建议

- `BAD_REQUEST`：参数非法（负数资源、端口非法等）
- `NOT_FOUND`：心跳节点不存在（如果采用严格模式）
- `CONFLICT`：并发更新冲突
- `SERVICE_UNAVAILABLE`：节点管理服务临时不可用

---

## 8. 测试计划

## 8.1 单元测试

1. 注册新节点成功
2. 重复注册走更新分支
3. 心跳刷新时间与可用资源
4. 超时判断函数正确

## 8.2 集成测试

1. 节点注册 -> 心跳 -> 查询完整链路
2. 节点超时自动下线
3. 下线节点恢复心跳后回到 `ONLINE`
4. 高并发心跳写入不出现脏覆盖

---

## 9. 验收标准（Definition of Done）

1. 节点可完成注册、心跳、查询三类基本操作
2. 超时节点能自动切为 `OFFLINE`
3. 重复注册与重复心跳具备幂等行为
4. 节点状态可稳定反映真实健康状况
5. 为 P2-2 提供可用节点池输入

---

## 10. 实施顺序（建议 1.5~2 天）

Day 1：

- DTO + Controller + Service + Mapper
- 注册/心跳/查询主流程

Day 2：

- 超时自动下线任务
- 联调与回归测试
- 文档补充与参数调优

