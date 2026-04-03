# 实体类清单 (Entity Classes)
## 分布式轻量级调度系统 - Distributed Lite Scheduler V1

---

## 📋 实体类概览

本项目共包含 **14个核心实体类**，对应数据库中的14张表。所有实体类位于：
```
src/main/java/com/imperium/distributed_lite_scheduler_v1/model/entity/
```

---

## 🏗️ 实体类分类

### 1️⃣ 用户模块 (User Module)

| 实体类 | 对应表 | 说明 | 关键特性 |
|--------|-------|------|----------|
| `User` | `user` | 用户基础信息 | 软删除、唯一约束(username/email) |
| `Tenant` | `tenant` | 租户信息 | 软删除、租户编码唯一 |
| `TenantMember` | `tenant_member` | 租户成员关系 | 联合唯一约束(tenant_id, user_id) |

**关键字段说明**：
- `User.passwordHash`: 使用bcrypt加密存储
- `Tenant.expireTime`: NULL表示永久有效
- `TenantMember.role`: OWNER/ADMIN/MEMBER/GUEST

---

### 2️⃣ 任务模块 (Task Module)

| 实体类 | 对应表 | 说明 | 关键特性 |
|--------|-------|------|----------|
| `Project` | `project` | 项目空间 | 软删除、租户内项目编码唯一 |
| `Task` | `task` | 任务定义 | 软删除、乐观锁、项目内任务编码唯一 |
| `TaskInstance` | `task_instance` | 任务实例 | 乐观锁、实例编码全局唯一 |
| `TaskDependency` | `task_dependency` | 任务依赖关系(DAG) | 联合唯一约束(parent, child) |

**关键字段说明**：
- `Task.executorConfig`: JSON格式，存储执行器配置
- `Task.resourceRequire`: JSON格式，声明资源需求
- `TaskInstance.instanceCode`: 用于幂等性保证
- `TaskDependency.dependencyType`: SUCCESS/FAILED/FINISHED

---

### 3️⃣ 工作流模块 (Workflow Module)

| 实体类 | 对应表 | 说明 | 关键特性 |
|--------|-------|------|----------|
| `Workflow` | `workflow` | 工作流定义 | 软删除、乐观锁、项目内工作流编码唯一 |
| `WorkflowInstance` | `workflow_instance` | 工作流实例 | 实例编码全局唯一 |

**关键字段说明**：
- `Workflow.dagJson`: JSON格式，定义DAG结构
- `Workflow.cronExpression`: Cron表达式，定义调度周期
- `WorkflowInstance.totalTasks`: 工作流包含的任务总数
- `WorkflowInstance.successTasks/failedTasks`: 成功/失败任务计数

---

### 4️⃣ 资源模块 (Resource Module)

| 实体类 | 对应表 | 说明 | 关键特性 |
|--------|-------|------|----------|
| `ResourceNode` | `resource_node` | 资源节点 | 乐观锁、主机+端口唯一 |
| `ResourceQuota` | `resource_quota` | 租户资源配额 | 乐观锁、租户ID唯一 |

**关键字段说明**：
- `ResourceNode.nodeType`: CPU/GPU/MIXED
- `ResourceNode.labels`: JSON格式，用于任务节点匹配
- `ResourceQuota.maxCpu/usedCpu`: CPU配额管理
- `ResourceQuota.version`: 乐观锁，防止资源超卖

---

### 5️⃣ 监控模块 (Monitoring Module)

| 实体类 | 对应表 | 说明 | 关键特性 |
|--------|-------|------|----------|
| `ExecutionLog` | `execution_log` | 任务执行日志 | 按任务实例分组 |
| `AlertRule` | `alert_rule` | 告警规则 | 租户级配置 |
| `MetricSnapshot` | `metric_snapshot` | 性能指标快照 | 时序数据 |

**关键字段说明**：
- `ExecutionLog.logLevel`: DEBUG/INFO/WARN/ERROR
- `ExecutionLog.source`: STDOUT/STDERR/SYSTEM
- `AlertRule.conditionConfig`: JSON格式，定义触发条件
- `MetricSnapshot.metricType`: CPU_USAGE/MEMORY_USAGE/GPU_USAGE

---

## 🔧 技术规范

### MyBatis-Plus 注解使用

#### 1. 主键策略
```java
@TableId(value = "id", type = IdType.AUTO)
private Long id;
```
- 所有表使用自增主键

#### 2. 表名映射
```java
@TableName("user")
public class User { }
```
- 实体类名与表名不一致时使用

#### 3. 字段自动填充
```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createdAt;

@TableField(fill = FieldFill.INSERT_UPDATE)
private LocalDateTime updatedAt;
```
- `createdAt`: 插入时自动填充
- `updatedAt`: 插入和更新时自动填充

#### 4. 逻辑删除
```java
@TableLogic
private Integer deleted;
```
- 0: 未删除
- 1: 已删除
- 查询时自动过滤已删除数据

#### 5. 乐观锁
```java
@Version
private Integer version;
```
- 用于并发控制
- 更新时自动版本号+1
- 关键表：`Task`, `TaskInstance`, `ResourceNode`, `ResourceQuota`, `Workflow`

---

## 📦 实体类依赖关系

```
User ──┬──► Tenant (owner_user_id)
       │
       └──► TenantMember (user_id)
                │
                └──► Tenant (tenant_id)
                        │
                        ├──► Project (tenant_id)
                        │      │
                        │      ├──► Task (project_id)
                        │      │      │
                        │      │      ├──► TaskInstance (task_id)
                        │      │      │
                        │      │      └──► TaskDependency (parent_task_id/child_task_id)
                        │      │
                        │      └──► Workflow (project_id)
                        │             │
                        │             └──► WorkflowInstance (workflow_id)
                        │                    │
                        │                    └──► TaskInstance (workflow_instance_id)
                        │
                        └──► ResourceQuota (tenant_id)

ResourceNode ──► TaskInstance (resource_node_id)

TaskInstance ──┬──► ExecutionLog (task_instance_id)
               │
               └──► MetricSnapshot (resource_id)

Tenant ──► AlertRule (tenant_id)
```

---

## 🎯 使用示例

### 示例 1: 创建用户
```java
User user = new User();
user.setUsername("admin");
user.setEmail("admin@example.com");
user.setPasswordHash(BCrypt.hashpw("password", BCrypt.gensalt()));
user.setStatus(1);
userMapper.insert(user);  // createdAt/updatedAt 自动填充
```

### 示例 2: 创建租户并添加成员
```java
// 创建租户
Tenant tenant = new Tenant();
tenant.setTenantName("示例租户");
tenant.setTenantCode("demo-tenant");
tenant.setOwnerUserId(1L);
tenant.setStatus(1);
tenantMapper.insert(tenant);

// 添加成员
TenantMember member = new TenantMember();
member.setTenantId(tenant.getId());
member.setUserId(2L);
member.setRole("MEMBER");
tenantMemberMapper.insert(member);
```

### 示例 3: 创建任务定义
```java
Task task = new Task();
task.setProjectId(1L);
task.setTaskName("每日数据备份");
task.setTaskCode("daily-backup");
task.setTaskType("SHELL");
task.setExecutorConfig("{\"script\": \"mysqldump -u root mydb > backup.sql\"}");
task.setResourceRequire("{\"cpu\": 1, \"memory\": \"2Gi\"}");
task.setTimeoutSeconds(3600);
task.setRetryTimes(3);
task.setStatus(1);
taskMapper.insert(task);
```

### 示例 4: 提交任务实例（带乐观锁）
```java
TaskInstance instance = new TaskInstance();
instance.setTaskId(1L);
instance.setInstanceCode("backup_20260403_001");
instance.setStatus("PENDING");
instance.setPriority(5);
taskInstanceMapper.insert(instance);

// 更新状态（乐观锁）
instance.setStatus("RUNNING");
instance.setStartTime(LocalDateTime.now());
int updated = taskInstanceMapper.updateById(instance);
if (updated == 0) {
    // 版本冲突，重试
}
```

### 示例 5: 创建任务依赖（DAG）
```java
// extract → transform → load
TaskDependency dep1 = new TaskDependency();
dep1.setParentTaskId(1L);  // extract
dep1.setChildTaskId(2L);   // transform
dep1.setDependencyType("SUCCESS");
taskDependencyMapper.insert(dep1);

TaskDependency dep2 = new TaskDependency();
dep2.setParentTaskId(2L);  // transform
dep2.setChildTaskId(3L);   // load
dep2.setDependencyType("SUCCESS");
taskDependencyMapper.insert(dep2);
```

### 示例 6: 资源分配（乐观锁）
```java
// 查找可用节点
ResourceNode node = resourceNodeMapper.selectOne(
    new QueryWrapper<ResourceNode>()
        .eq("status", "ONLINE")
        .ge("available_cpu", 2)
        .ge("available_memory_mb", 4096)
        .last("LIMIT 1")
);

// 分配资源（乐观锁）
node.setAvailableCpu(node.getAvailableCpu() - 2);
node.setAvailableMemoryMb(node.getAvailableMemoryMb() - 4096);
int updated = resourceNodeMapper.updateById(node);
if (updated == 0) {
    // 资源已被占用，重新选择节点
}
```

---

## ✅ 实体类验证清单

| ✔️ | 实体类 | 表映射 | 主键 | 软删除 | 乐观锁 | 自动填充 |
|:--:|--------|--------|:----:|:------:|:------:|:--------:|
| ✅ | User | ✓ | ✓ | ✓ | - | ✓ |
| ✅ | Tenant | ✓ | ✓ | ✓ | - | ✓ |
| ✅ | TenantMember | ✓ | ✓ | - | - | ✓ |
| ✅ | Project | ✓ | ✓ | ✓ | - | ✓ |
| ✅ | Task | ✓ | ✓ | ✓ | ✓ | ✓ |
| ✅ | TaskInstance | ✓ | ✓ | - | ✓ | ✓ |
| ✅ | TaskDependency | ✓ | ✓ | - | - | ✓ |
| ✅ | Workflow | ✓ | ✓ | ✓ | ✓ | ✓ |
| ✅ | WorkflowInstance | ✓ | ✓ | - | - | ✓ |
| ✅ | ResourceNode | ✓ | ✓ | - | ✓ | ✓ |
| ✅ | ResourceQuota | ✓ | ✓ | - | ✓ | ✓ |
| ✅ | ExecutionLog | ✓ | ✓ | - | - | ✓ |
| ✅ | AlertRule | ✓ | ✓ | - | - | ✓ |
| ✅ | MetricSnapshot | ✓ | ✓ | - | - | ✓ |

**统计**：
- 总实体类数：14
- 支持软删除：6 (User, Tenant, Project, Task, Workflow)
- 支持乐观锁：5 (Task, TaskInstance, Workflow, ResourceNode, ResourceQuota)
- 全部支持自动填充：14

---

## 🔗 相关文档
- [数据库设计文档](./DATABASE_DESIGN.md)
- [数据库Schema](./schema.sql)
- [用户故事文档](./USER_STORIES.md)

---

## 📌 注意事项

### 1. 字段命名规范
- 数据库字段使用下划线命名：`user_id`, `created_at`
- Java字段使用驼峰命名：`userId`, `createdAt`
- MyBatis-Plus自动转换

### 2. JSON字段处理
以下字段存储JSON字符串，需在业务层序列化/反序列化：
- `Task.executorConfig`
- `Task.resourceRequire`
- `Workflow.dagJson`
- `ResourceNode.labels`
- `AlertRule.conditionConfig`
- `AlertRule.notificationChannels`

建议使用Jackson或Fastjson：
```java
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(config);
Config config = mapper.readValue(json, Config.class);
```

### 3. 软删除注意事项
- 软删除字段不会物理删除数据
- 查询时自动添加 `WHERE deleted = 0`
- 需要查询已删除数据时使用：`@SqlParser(filter = true)`

### 4. 乐观锁使用建议
- 更新前必须先查询获取version
- 更新失败（返回0）需重试
- 适用场景：资源分配、状态更新

### 5. 时间类型统一
- 全部使用 `LocalDateTime`（Java 8+）
- 替代过时的 `java.util.Date`
- 需配置类型处理器或使用MyBatis-Plus默认配置

---

**创建时间**: 2026-04-03  
**最后更新**: 2026-04-03  
**版本**: v1.0
