# 多租户落地设计稿（策略B）

## 1. 文档目标

本文档将以下内容合并为一份可执行方案，供设计评审与开发拆解使用：

- 租户与用户关系（讲清楚概念）
- API 设计（登录、切租户、成员管理、鉴权）
- JWT Claims 设计
- 关键 SQL 过滤模板
- 调度器租户公平队列规则

策略选择：**策略B（注册只创建账号，不自动创建租户）**。

---

## 2. 先讲懂：租户和用户是什么关系

### 2.1 一句话定义

- `User`：全局身份账号（“人”），负责登录认证。
- `Tenant`：业务空间/组织边界（“公司或团队”），负责数据和权限隔离。
- `TenantMember`：用户与租户关系（“这个人在这个租户里的角色”）。

### 2.2 数据关系

- `User` 与 `Tenant` 是 **N:N（多对多）**
- 通过 `tenant_member(tenant_id, user_id, role)` 建立关系
- 角色是租户内角色：`OWNER / ADMIN / MEMBER / GUEST`

### 2.3 为什么必须有租户

没有租户边界就无法稳定实现：

1. 数据隔离（A租户看不到B租户任务）
2. 权限隔离（同一用户在不同租户角色不同）
3. 资源隔离（配额、并发、调度公平都按租户）

### 2.4 现实类比

- 用户 = 真实员工（身份唯一）
- 租户 = 公司/部门空间
- 成员关系 = 工牌权限（在不同公司权限不同）

例如：用户 Alice 在租户 A 是 `OWNER`，在租户 B 是 `MEMBER`。

---

## 3. 总体落地原则（策略B）

### 3.1 用户生命周期

1. 注册：仅创建 `user`
2. 登录：获得身份令牌，并返回可访问租户列表
3. 创建租户或加入租户：产生 `tenant_member`
4. 选择当前租户：切换到租户上下文
5. 业务请求：必须在租户上下文中执行

### 3.2 安全主线

每个请求按以下顺序执行：

1. 认证：你是谁（User）
2. 租户上下文：你在哪个租户（Tenant）
3. 鉴权：你在这个租户能做什么（TenantMember.role + 资源归属）

---

## 4. API 设计

## 4.1 认证与租户上下文

### `POST /api/auth/register`

- 作用：注册账号
- 入参：`username`, `email`, `password`
- 行为：只创建 `user`，不创建 `tenant`

### `POST /api/auth/login`

- 作用：登录
- 入参：账号 + 密码
- 出参建议：
  - `accessToken`
  - `refreshToken`（可选）
  - `tenants[]`（可访问租户列表，含 `tenantId/tenantCode/tenantName/role`）
  - `defaultTenantId`（可选）

### `GET /api/auth/tenants`

- 作用：获取当前用户可访问租户列表（用于前端租户切换器）
- 权限：已登录

### `POST /api/auth/switch-tenant`

- 作用：切换当前租户上下文
- 入参：`tenantId` 或 `tenantCode`
- 校验：
  1. 当前用户在 `tenant_member` 中存在
  2. 租户状态有效（未禁用、未过期）
- 出参：新的 tenant-scoped access token（推荐）

## 4.2 租户与成员管理

### `POST /api/tenants/create`

- 作用：创建租户
- 权限：已登录用户
- 事务内必须同时完成：
  1. 插入 `tenant`
  2. 插入 `tenant_member(role=OWNER)`
  3. 插入 `resource_quota` 默认配额

### `GET /api/tenants/{tenantId}/members`

- 作用：查看成员列表
- 建议权限：`OWNER/ADMIN/MEMBER/GUEST`（可按需求收紧）

### `POST /api/tenants/{tenantId}/members`

- 作用：邀请或添加成员
- 入参：`userId/email`, `role`
- 权限规则：
  - `OWNER`：可邀请任意角色
  - `ADMIN`：只可邀请 `MEMBER/GUEST`
  - `MEMBER/GUEST`：无邀请权限

### `PATCH /api/tenants/{tenantId}/members/{userId}/role`

- 作用：修改成员角色
- 权限：`OWNER`
- 约束：租户必须至少保留一个 `OWNER`

### `DELETE /api/tenants/{tenantId}/members/{userId}`

- 作用：移除成员
- 权限：`OWNER` 或 `ADMIN`（ADMIN不能操作OWNER）
- 约束：不能移除最后一个 `OWNER`

## 4.3 鉴权判定入口（建议统一中间件/服务）

建议统一抽象：

`authorize(userId, tenantId, permission, resourceType, resourceId)`

判定顺序：

1. 用户是否为租户成员
2. 租户状态是否有效
3. 角色是否具备 `permission`
4. 资源是否归属 `tenantId`（防跨租户越权）

---

## 5. JWT Claims 设计

建议采用双层令牌语义：

## 5.1 身份令牌（Identity Token）

用于表示“你是谁”，不绑定具体租户。

```json
{
  "sub": "10001",
  "username": "alice",
  "token_type": "identity",
  "iat": 1710000000,
  "exp": 1710003600
}
```

## 5.2 租户访问令牌（Tenant Access Token）

用于业务请求，绑定当前租户和角色。

```json
{
  "sub": "10001",
  "username": "alice",
  "token_type": "tenant_access",
  "tenant_id": 20001,
  "tenant_code": "acme_data",
  "tenant_role": "ADMIN",
  "permissions_version": 3,
  "iat": 1710000000,
  "exp": 1710001800
}
```

## 5.3 字段建议

- `tenant_id/tenant_code`：请求上下文边界
- `tenant_role`：快速鉴权依据
- `permissions_version`：角色变更后令牌失效控制（可选增强）

## 5.4 安全建议

- Access Token 短时效（15~30分钟）
- Refresh Token 中长时效（7~30天）
- 切租户时签发新的 tenant token
- 删除租户/转移Owner等敏感操作必须实时查库，不仅依赖 token

---

## 6. 关键 SQL 过滤模板（防跨租户）

核心原则：**任何业务查询都必须能收敛到 tenant_id 条件**。

## 6.1 直接 tenant 字段（project）

```sql
SELECT *
FROM project p
WHERE p.tenant_id = :tenantId
  AND p.deleted = 0;
```

## 6.2 间接归属（task -> project -> tenant）

```sql
SELECT t.*
FROM task t
JOIN project p ON p.id = t.project_id
WHERE p.tenant_id = :tenantId
  AND p.deleted = 0
  AND t.deleted = 0;
```

## 6.3 更新语句同样必须带 tenant 约束

```sql
UPDATE task t
JOIN project p ON p.id = t.project_id
SET t.status = :status
WHERE t.id = :taskId
  AND p.tenant_id = :tenantId;
```

## 6.4 详情查询防漏（按 ID 查最容易漏）

```sql
SELECT t.*
FROM task t
JOIN project p ON p.id = t.project_id
WHERE t.id = :taskId
  AND p.tenant_id = :tenantId;
```

## 6.5 成员校验模板

```sql
SELECT tm.role
FROM tenant_member tm
JOIN tenant t ON t.id = tm.tenant_id
WHERE tm.tenant_id = :tenantId
  AND tm.user_id = :userId
  AND t.status = 1
  AND (t.expire_time IS NULL OR t.expire_time > NOW());
```

---

## 7. 调度器租户公平队列规则

## 7.1 目标

1. 防止大租户长期占满资源
2. 保留任务优先级能力
3. 严格执行租户配额

## 7.2 推荐模型：两级队列

- 一级：按租户分桶（Tenant Queue）
- 二级：租户内优先级队列（Priority + FIFO）

## 7.3 调度算法：WRR + 配额门控

每轮调度流程：

1. 选出“有 READY 任务且配额可用”的租户集合
2. 用加权轮询（WRR）选择租户
   - 权重来源：套餐等级 / 最大资源配额 / 业务优先级
3. 从租户队列取任务并分配资源
4. 成功后扣减租户配额与节点资源

## 7.4 防饿死机制

- Aging：任务等待越久，等效优先级逐步提高
- 每租户保底份额（minimum scheduling share）

## 7.5 配额硬约束（必须）

调度前必须满足：

- `used_cpu + required_cpu <= max_cpu`
- `used_memory + required_memory <= max_memory`
- `used_gpu + required_gpu <= max_gpu`
- `running_tasks < max_running_tasks`

若不满足，保持 `READY`，进入下一轮。

## 7.6 节点层策略

在“先选租户”之后，再做节点选型（Best Fit 或加权评分）：

- 避免破坏租户公平
- 保持节点资源利用率

---

## 8. 角色-权限建议矩阵

- `OWNER`：租户设置、成员管理、项目任务全权限
- `ADMIN`：项目/任务管理、部分成员管理
- `MEMBER`：创建与执行任务，不可做租户级管理
- `GUEST`：只读

建议将操作抽象为 `permission`，如：

- `tenant:member:add`
- `tenant:member:remove`
- `project:create`
- `task:run`
- `task:delete`

然后由角色映射到权限集合，减少硬编码判断。

---

## 9. 一致性与约束规则（必须落地）

1. `tenant.owner_user_id` 必须与 `tenant_member(role=OWNER)` 保持一致
2. 任何时刻至少保留一个 `OWNER`
3. 创建租户、写入 OWNER 成员、创建配额必须同事务
4. 敏感操作（删租户/转Owner）必须二次校验并实时查库

---

## 10. 常见踩坑清单

1. 只校验 user，不校验 tenant，导致跨租户越权
2. 按资源 id 直接查，不加 tenant 条件
3. 角色变更后旧 token 继续有效，导致权限漂移
4. 调度只看全局优先级，导致小租户被饿死
5. owner 字段和成员表不一致，导致权限异常

---

## 11. 最终记忆版

> 用户是登录身份，租户是隔离边界，成员关系是权限载体。  
> 用户只有进入某个租户上下文，才有权访问该租户的数据和调度资源。

