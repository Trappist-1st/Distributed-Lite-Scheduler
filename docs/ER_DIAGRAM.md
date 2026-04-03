# 分布式轻量级调度系统 - ER图设计

## 📊 实体关系图（Entity-Relationship Diagram）

### 核心实体总览（14个实体）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    分布式轻量级调度系统 ER图                              │
│                                                                          │
│  ┌──────────┐                                                            │
│  │  User    │                                                            │
│  │          │                                                            │
│  │ PK: id   │───────┐                                                    │
│  │ username │       │                                                    │
│  │ email    │       │ N                                                  │
│  │ password │       │                                                    │
│  └──────────┘       │                                                    │
│        │            │                                                    │
│        │ 1          │                                                    │
│        │            ▼                                                    │
│        │      ┌─────────────┐                                           │
│        │      │TenantMember │                                           │
│        │      │             │                                           │
│        │      │ PK: id      │                                           │
│        │      │ FK: tenant  │◄────┐                                     │
│        └─────►│ FK: user    │     │                                     │
│               │ role        │     │ N                                   │
│               └─────────────┘     │                                     │
│                                   │                                     │
│                                   │                                     │
│  ┌──────────┐                    │                                     │
│  │  Tenant  │                    │                                     │
│  │          │                    │                                     │
│  │ PK: id   │────────────────────┘                                     │
│  │ name     │                                                           │
│  │ code     │───────┐                                                   │
│  │ owner    │       │ 1                                                 │
│  └──────────┘       │                                                   │
│        │            │                                                   │
│        │ 1          │                                                   │
│        │            │                                                   │
│        ▼            ▼                                                   │
│  ┌─────────────┐  ┌───────────────┐                                   │
│  │ResourceQuota│  │   Project     │                                   │
│  │             │  │               │                                   │
│  │ PK: id      │  │ PK: id        │                                   │
│  │ FK: tenant  │  │ FK: tenant    │───────┐                           │
│  │ max_cpu     │  │ name          │       │ 1                         │
│  │ used_cpu    │  │ code          │       │                           │
│  └─────────────┘  └───────────────┘       │                           │
│                           │                │                           │
│                           │ 1              │                           │
│                           │                │                           │
│                           ▼                ▼                           │
│                   ┌──────────┐    ┌────────────┐                      │
│                   │   Task   │    │  Workflow  │                      │
│                   │          │    │            │                      │
│                   │ PK: id   │    │ PK: id     │                      │
│                   │ FK: proj │    │ FK: proj   │───────┐              │
│                   │ name     │    │ dag_json   │       │ 1            │
│                   │ type     │    │ cron_expr  │       │              │
│                   │ config   │    └────────────┘       │              │
│                   └──────────┘                         │              │
│                         │                              │              │
│                         │ N                            │              │
│                         │                              │              │
│         ┌───────────────┼──────────────┐               │              │
│         │               │              │               │              │
│         │ 1             │ N            │ N             │              │
│         │               │              │               │              │
│         ▼               ▼              ▼               ▼              │
│  ┌─────────────┐ ┌──────────────┐ ┌─────────────┐ ┌──────────────┐  │
│  │TaskInstance │ │TaskDependency│ │   (Self)    │ │WorkflowInst  │  │
│  │             │ │              │ │             │ │              │  │
│  │ PK: id      │ │ PK: id       │ │             │ │ PK: id       │  │
│  │ FK: task    │ │ FK: parent   │ │             │ │ FK: workflow │  │
│  │ FK: wf_inst │ │ FK: child    │ │             │ │ status       │  │
│  │ FK: node    │ │ depend_type  │ │             │ │ total_tasks  │  │
│  │ status      │ └──────────────┘ │             │ └──────────────┘  │
│  │ start_time  │                  │             │         │          │
│  │ end_time    │                  │             │         │ 1        │
│  └─────────────┘                  │             │         │          │
│         │                         │             │         │          │
│         │ N                       │             │         │          │
│         │                         │             │         │          │
│         ▼                         │             │         │          │
│  ┌─────────────┐                  │             │         │          │
│  │ExecutionLog │                  │             │         └──────────┘
│  │             │                  │             │                     │
│  │ PK: id      │                  │             │                     │
│  │ FK: task_in │                  │             │                     │
│  │ log_level   │                  │             │                     │
│  │ log_content │                  │             │                     │
│  │ log_time    │                  │             │                     │
│  └─────────────┘                  │             │                     │
│                                   │             │                     │
│  ┌──────────────┐                 │             │                     │
│  │ResourceNode  │                 │             │                     │
│  │              │                 │             │                     │
│  │ PK: id       │─────────────────┘             │                     │
│  │ host         │                               │                     │
│  │ port         │                               │                     │
│  │ total_cpu    │                               │                     │
│  │ avail_cpu    │                               │                     │
│  │ total_gpu    │                               │                     │
│  │ avail_gpu    │                               │                     │
│  │ status       │                               │                     │
│  └──────────────┘                               │                     │
│         │                                       │                     │
│         │ 1                                     │                     │
│         │                                       │                     │
│         ▼                                       │                     │
│  ┌──────────────┐                               │                     │
│  │MetricSnapshot│                               │                     │
│  │              │                               │                     │
│  │ PK: id       │                               │                     │
│  │ FK: resource │                               │                     │
│  │ metric_type  │                               │                     │
│  │ metric_value │                               │                     │
│  └──────────────┘                               │                     │
│                                                 │                     │
│  ┌──────────────┐                               │                     │
│  │  AlertRule   │                               │                     │
│  │              │                               │                     │
│  │ PK: id       │                               │                     │
│  │ FK: tenant   │───────────────────────────────┘                     │
│  │ target_type  │                                                     │
│  │ target_id    │                                                     │
│  │ condition    │                                                     │
│  └──────────────┘                                                     │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

## 实体详细说明

### 1. User（用户）
- **主键**: id
- **核心属性**: username, email, password_hash
- **关系**:
  - 通过 TenantMember 与 Tenant 多对多关联
  - 创建 Project（1:N）
  - 触发 TaskInstance（1:N）

### 2. Tenant（租户）
- **主键**: id
- **唯一键**: tenant_code
- **核心属性**: tenant_name, owner_user_id
- **关系**:
  - 拥有多个 Project（1:N）
  - 拥有一个 ResourceQuota（1:1）
  - 通过 TenantMember 与 User 多对多关联

### 3. TenantMember（租户成员）
- **主键**: id
- **唯一键**: (tenant_id, user_id)
- **核心属性**: role (OWNER/ADMIN/MEMBER/GUEST)
- **关系**: 实现 User 和 Tenant 的多对多关联

### 4. Project（项目）
- **主键**: id
- **唯一键**: (tenant_id, project_code)
- **核心属性**: project_name, creator_user_id
- **关系**:
  - 属于一个 Tenant（N:1）
  - 包含多个 Task（1:N）
  - 包含多个 Workflow（1:N）

### 5. Task（任务定义）
- **主键**: id
- **唯一键**: (project_id, task_code)
- **核心属性**: task_type, executor_config, resource_require
- **关系**:
  - 属于一个 Project（N:1）
  - 产生多个 TaskInstance（1:N）
  - 通过 TaskDependency 与其他 Task 关联（DAG）

### 6. TaskInstance（任务实例）
- **主键**: id
- **唯一键**: instance_code
- **核心属性**: status, start_time, end_time, duration_ms
- **关系**:
  - 继承自一个 Task（N:1）
  - 可能属于一个 WorkflowInstance（N:1，可选）
  - 分配到一个 ResourceNode（N:1，可选）
  - 产生多个 ExecutionLog（1:N）

### 7. TaskDependency（任务依赖）
- **主键**: id
- **唯一键**: (parent_task_id, child_task_id)
- **核心属性**: dependency_type (SUCCESS/FAILED/FINISHED)
- **关系**: 表示 Task 之间的 DAG 依赖关系（N:N）

### 8. Workflow（工作流定义）
- **主键**: id
- **唯一键**: (project_id, workflow_code)
- **核心属性**: dag_json, cron_expression
- **关系**:
  - 属于一个 Project（N:1）
  - 产生多个 WorkflowInstance（1:N）

### 9. WorkflowInstance（工作流实例）
- **主键**: id
- **唯一键**: instance_code
- **核心属性**: status, total_tasks, success_tasks, failed_tasks
- **关系**:
  - 继承自一个 Workflow（N:1）
  - 包含多个 TaskInstance（1:N）

### 10. ResourceNode（资源节点）
- **主键**: id
- **唯一键**: (node_host, node_port)
- **核心属性**: total_cpu, available_cpu, total_gpu, available_gpu
- **关系**:
  - 执行多个 TaskInstance（1:N）
  - 产生多个 MetricSnapshot（1:N）

### 11. ResourceQuota（租户资源配额）
- **主键**: id
- **唯一键**: tenant_id
- **核心属性**: max_cpu, used_cpu, max_gpu, used_gpu
- **关系**: 属于一个 Tenant（1:1）

### 12. ExecutionLog（执行日志）
- **主键**: id
- **核心属性**: log_level, log_content, log_time
- **关系**: 属于一个 TaskInstance（N:1）

### 13. AlertRule（告警规则）
- **主键**: id
- **核心属性**: rule_type, target_type, target_id, condition_config
- **关系**: 属于一个 Tenant（N:1）

### 14. MetricSnapshot（性能指标快照）
- **主键**: id
- **核心属性**: resource_type, resource_id, metric_type, metric_value
- **关系**: 关联到 ResourceNode 或 TaskInstance（多态关联）

## 关系类型汇总

| 关系 | 类型 | 说明 |
|------|------|------|
| User ↔ Tenant | N:N | 通过 TenantMember 实现 |
| Tenant → Project | 1:N | 一个租户多个项目 |
| Tenant ↔ ResourceQuota | 1:1 | 每个租户一个配额 |
| Project → Task | 1:N | 一个项目多个任务 |
| Project → Workflow | 1:N | 一个项目多个工作流 |
| Task → TaskInstance | 1:N | 一个任务定义多个实例 |
| Task ↔ Task | N:N | 通过 TaskDependency 实现 DAG |
| Workflow → WorkflowInstance | 1:N | 一个工作流多个实例 |
| WorkflowInstance → TaskInstance | 1:N | 一个工作流实例包含多个任务实例 |
| ResourceNode → TaskInstance | 1:N | 一个节点执行多个任务 |
| TaskInstance → ExecutionLog | 1:N | 一个实例多条日志 |
| ResourceNode → MetricSnapshot | 1:N | 一个节点多个性能快照 |
| Tenant → AlertRule | 1:N | 一个租户多个告警规则 |

## 参照完整性约束

### 主要外键约束

```sql
-- TenantMember
ALTER TABLE tenant_member 
  ADD CONSTRAINT fk_tenant_member_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  ADD CONSTRAINT fk_tenant_member_user FOREIGN KEY (user_id) REFERENCES user(id);

-- Project
ALTER TABLE project 
  ADD CONSTRAINT fk_project_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  ADD CONSTRAINT fk_project_creator FOREIGN KEY (creator_user_id) REFERENCES user(id);

-- Task
ALTER TABLE task 
  ADD CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project(id),
  ADD CONSTRAINT fk_task_creator FOREIGN KEY (creator_user_id) REFERENCES user(id);

-- TaskInstance
ALTER TABLE task_instance 
  ADD CONSTRAINT fk_task_instance_task FOREIGN KEY (task_id) REFERENCES task(id),
  ADD CONSTRAINT fk_task_instance_workflow FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instance(id),
  ADD CONSTRAINT fk_task_instance_node FOREIGN KEY (resource_node_id) REFERENCES resource_node(id),
  ADD CONSTRAINT fk_task_instance_user FOREIGN KEY (trigger_user_id) REFERENCES user(id);

-- TaskDependency
ALTER TABLE task_dependency 
  ADD CONSTRAINT fk_task_dep_parent FOREIGN KEY (parent_task_id) REFERENCES task(id),
  ADD CONSTRAINT fk_task_dep_child FOREIGN KEY (child_task_id) REFERENCES task(id);

-- Workflow
ALTER TABLE workflow 
  ADD CONSTRAINT fk_workflow_project FOREIGN KEY (project_id) REFERENCES project(id),
  ADD CONSTRAINT fk_workflow_creator FOREIGN KEY (creator_user_id) REFERENCES user(id);

-- WorkflowInstance
ALTER TABLE workflow_instance 
  ADD CONSTRAINT fk_workflow_instance_workflow FOREIGN KEY (workflow_id) REFERENCES workflow(id),
  ADD CONSTRAINT fk_workflow_instance_user FOREIGN KEY (trigger_user_id) REFERENCES user(id);

-- ResourceQuota
ALTER TABLE resource_quota 
  ADD CONSTRAINT fk_quota_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- ExecutionLog
ALTER TABLE execution_log 
  ADD CONSTRAINT fk_log_task_instance FOREIGN KEY (task_instance_id) REFERENCES task_instance(id);

-- AlertRule
ALTER TABLE alert_rule 
  ADD CONSTRAINT fk_alert_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
```

## 数据完整性检查点

### 1. 业务规则约束

- ✅ **租户配额一致性**: `resource_quota.used_* <= max_*`
- ✅ **资源节点容量**: `resource_node.available_* <= total_*`
- ✅ **任务实例唯一性**: `instance_code` 全局唯一（幂等性）
- ✅ **工作流实例唯一性**: `instance_code` 全局唯一
- ✅ **DAG无环性**: TaskDependency 不能形成循环（应用层检查）

### 2. 级联删除规则

- **软删除**: Project 删除时级联软删除 Task 和 Workflow
- **保留历史**: TaskInstance 和 WorkflowInstance 永久保留（用于审计）
- **日志归档**: ExecutionLog 定期归档到对象存储

### 3. 乐观锁字段

以下表使用 `version` 字段实现乐观锁：
- `task` - 防止并发修改任务配置
- `task_instance` - 防止并发更新任务状态
- `resource_node` - 防止资源超额分配
- `resource_quota` - 防止配额超限
- `workflow` - 防止并发修改工作流

## ER图设计原则总结

1. **规范化**: 严格遵循第三范式，减少数据冗余
2. **可扩展性**: JSON字段支持灵活配置
3. **审计性**: 所有表包含 created_at, updated_at
4. **软删除**: 关键实体支持 deleted 标记
5. **并发控制**: 关键表使用 version 乐观锁
6. **性能优化**: 适度反范式（如 workflow_instance 统计字段）

---

**注意**: 建议使用专业的ER图工具（如 draw.io、dbdiagram.io、MySQL Workbench）根据此文档绘制可视化ER图。
