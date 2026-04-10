# 项目与任务 CRUD 落地设计稿（P1-2）

## 1. 文档目标

本文档用于将 `P1-2: 项目与任务 CRUD` 从“任务列表”转成“可直接开发与联调”的执行方案，覆盖：

- API 设计（Project CRUD + Task CRUD + 批量创建任务）
- 多租户边界与鉴权规则（防跨租户越权）
- 参数校验规范（JSR303）
- 软删除设计与 SQL 模板
- 落地顺序、验收标准、测试清单

目标是为后续 `P1-3 任务状态机` 提供稳定的数据模型与接口基础。

---

## 2. 范围与非范围

### 2.1 本阶段范围（必须完成）

1. `project` 资源的增删改查接口
2. `task` 资源的增删改查接口
3. 批量创建任务接口
4. JSR303 参数校验（请求层）
5. 软删除实现（查询默认过滤已删除）

### 2.2 非本阶段范围（后续阶段处理）

1. 任务状态机流转约束（P1-3）
2. 调度相关字段与调度器行为（Phase 3）
3. 执行日志与可观测性（P1-4 / Phase 8）

---

## 3. 核心模型与租户边界

### 3.1 实体关系

- `project` 直接归属 `tenant_id`
- `task` 归属 `project_id`，通过 `project -> tenant` 间接归属租户

### 3.2 边界原则

所有项目与任务读写都必须绑定 `currentTenantId`，并满足：

1. 用户是当前租户成员（认证阶段已保证）
2. 资源属于当前租户（数据访问阶段再次保证）

一句话：**ID 可被伪造，tenant 条件不可省略**。

---

## 4. API 设计

统一前缀建议：`/api/projects`、`/api/tasks`

## 4.1 Project CRUD

### `POST /api/projects`

- 作用：创建项目
- 入参：`name`, `description`, `visibility`（可选）
- 行为：写入 `tenant_id=currentTenantId`
- 权限建议：`OWNER/ADMIN/MEMBER`

### `GET /api/projects`

- 作用：分页查询项目列表
- 入参：`page`, `size`, `keyword`（可选）
- 行为：仅返回 `tenant_id=currentTenantId and deleted=0`
- 权限建议：租户内全角色可读（可按需求收紧）

### `GET /api/projects/{projectId}`

- 作用：项目详情
- 行为：必须校验项目属于当前租户且 `deleted=0`

### `PUT /api/projects/{projectId}`

- 作用：更新项目
- 约束：只能更新本租户项目，且 `deleted=0`
- 建议不可变字段：`tenantId`, `creatorId`, `createTime`

### `DELETE /api/projects/{projectId}`

- 作用：软删除项目
- 行为：`deleted=1, deleted_at=now(), deleted_by=currentUserId`
- 级联策略：可选
  - A. 阻止删除（若存在未删除任务）
  - B. 级联软删除任务

建议本阶段采用 A（更安全，避免误删）。

## 4.2 Task CRUD

### `POST /api/tasks`

- 作用：创建任务
- 入参：`projectId`, `name`, `type`, `payload`（可选）
- 行为：
  1. 先校验 `projectId` 属于当前租户且未删除
  2. 再写入任务
- 初始状态：建议 `PENDING`（为 P1-3 做准备）

### `GET /api/tasks`

- 作用：分页查询任务
- 入参：`projectId`, `status`, `page`, `size`, `keyword`
- 行为：通过 `JOIN project` 按租户过滤

### `GET /api/tasks/{taskId}`

- 作用：任务详情
- 行为：必须 `JOIN project` 做租户边界过滤

### `PUT /api/tasks/{taskId}`

- 作用：更新任务基础信息
- 约束：
  - 不允许直接更新终态字段（`status` 的严格流转后置到 P1-3）
  - 只能更新本租户任务且未删除

### `DELETE /api/tasks/{taskId}`

- 作用：软删除任务
- 行为：`deleted=1, deleted_at=now(), deleted_by=currentUserId`

## 4.3 批量创建任务

### `POST /api/tasks/batch`

- 作用：批量创建任务（同租户内）
- 入参：`projectId` + `tasks[]`
- 限制建议：
  - 单次最多 100 条（可配置）
  - 名称去重策略：同项目内可选“拒绝重复 / 自动后缀”
- 事务建议：
  - 默认全有或全无（一个失败则整体回滚）
  - 返回每条失败明细可作为后续增强

---

## 5. DTO 与 JSR303 校验规范

## 5.1 Project 请求

- `name`: `@NotBlank @Size(max=64)`
- `description`: `@Size(max=500)`
- `visibility`（可选）: `@Pattern(regexp="PRIVATE|INTERNAL|PUBLIC")`

## 5.2 Task 请求

- `projectId`: `@NotNull @Positive`
- `name`: `@NotBlank @Size(max=128)`
- `type`: `@NotBlank @Size(max=32)`
- `payload`（可选）: `@Size(max=10000)`

## 5.3 Batch 请求

- `projectId`: `@NotNull @Positive`
- `tasks`: `@NotEmpty @Size(max=100)`
- `tasks[*].name/type`: 同单任务约束

## 5.4 Controller 规范

- `@Validated` + `@Valid`
- 参数错误统一映射为 400（结构化错误响应）
- 建议返回：`code`, `message`, `details[]`

---

## 6. 软删除设计

## 6.1 字段建议

`project`、`task` 增加以下字段（若已存在则复用）：

- `deleted` tinyint(1) default 0
- `deleted_at` datetime null
- `deleted_by` bigint null

## 6.2 查询约束

默认所有业务查询追加 `deleted = 0`。

如需“回收站”能力，另开显式接口，不与默认列表混用。

## 6.3 索引建议

- `project(tenant_id, deleted, create_time desc)`
- `task(project_id, deleted, create_time desc)`
- `task(status, deleted, create_time desc)`（列表筛选常用）

---

## 7. 关键 SQL 模板（防越权）

## 7.1 查询项目列表

```sql
SELECT p.*
FROM project p
WHERE p.tenant_id = :tenantId
  AND p.deleted = 0
ORDER BY p.create_time DESC
LIMIT :offset, :size;
```

## 7.2 按 ID 查项目（必须带 tenant）

```sql
SELECT p.*
FROM project p
WHERE p.id = :projectId
  AND p.tenant_id = :tenantId
  AND p.deleted = 0;
```

## 7.3 查询任务列表（通过 project 过滤租户）

```sql
SELECT t.*
FROM task t
JOIN project p ON p.id = t.project_id
WHERE p.tenant_id = :tenantId
  AND p.deleted = 0
  AND t.deleted = 0
ORDER BY t.create_time DESC
LIMIT :offset, :size;
```

## 7.4 按 ID 更新任务（防跨租户）

```sql
UPDATE task t
JOIN project p ON p.id = t.project_id
SET t.name = :name,
    t.type = :type,
    t.payload = :payload,
    t.update_time = NOW()
WHERE t.id = :taskId
  AND p.tenant_id = :tenantId
  AND p.deleted = 0
  AND t.deleted = 0;
```

## 7.5 软删除任务

```sql
UPDATE task t
JOIN project p ON p.id = t.project_id
SET t.deleted = 1,
    t.deleted_at = NOW(),
    t.deleted_by = :userId,
    t.update_time = NOW()
WHERE t.id = :taskId
  AND p.tenant_id = :tenantId
  AND t.deleted = 0;
```

---

## 8. 服务层落地建议

建议统一封装“归属校验”，避免散落在各服务里重复写判断：

- `assertProjectInTenant(projectId, tenantId)`
- `assertTaskInTenant(taskId, tenantId)`（内部通过 join project）

所有写操作先校验再执行，校验失败统一抛业务异常（404 或 403，按语义统一）。

---

## 9. 异常码与响应约定（建议）

- `400` 参数校验失败
- `401` 未认证
- `403` 无权限（角色不足）
- `404` 资源不存在或不属于当前租户（建议不暴露存在性）
- `409` 冲突（如重名、状态不允许）

建议错误响应可追踪字段：`traceId`（便于联调和日志定位）。

---

## 10. 实施顺序（建议 3 个迭代）

### 迭代 A：Project CRUD（最小可用）

1. Mapper + Service + Controller 打通
2. 租户过滤与软删除生效
3. 列表/详情/更新/删除联调通过

### 迭代 B：Task CRUD（含租户间接过滤）

1. 任务读写统一通过 `JOIN project` 做租户过滤
2. 单任务增删改查联调
3. 列表筛选（projectId/status/keyword）可用

### 迭代 C：Batch + 校验 + 收口

1. 批量创建接口
2. DTO 参数校验补齐
3. 异常码与统一响应收敛

---

## 11. 验收标准（Definition of Done）

1. Project CRUD 与 Task CRUD 共 10 个接口可用（含批量）
2. 任意按 ID 的查询/更新/删除都无法跨租户访问
3. 软删除后资源不出现在默认列表和详情中
4. 参数非法请求可返回结构化 400 错误
5. 至少覆盖以下测试：
   - 正常路径：创建/更新/查询/删除
   - 越权路径：跨租户访问返回 404/403
   - 软删除路径：删除后不可见
   - 批量路径：边界值（0 条、超上限、部分非法）

---

## 12. 常见踩坑清单

1. `task` 按 `id` 直接查，不做 `join project tenant` 过滤
2. 删除只改 `deleted`，但查询漏加 `deleted=0`
3. 批量创建逐条提交，失败后出现部分成功脏数据
4. Controller 未加 `@Valid`，导致校验规则形同虚设
5. 返回 403/404 语义混乱，前端难以处理

---

## 13. 最终记忆版

> P1-2 的本质不是“把 CRUD 写出来”，而是“把多租户边界写进每一次 CRUD”。  
> 只有做到“可查、可改、可删、不可越权”，后续状态机与调度器才不会反复返工。

