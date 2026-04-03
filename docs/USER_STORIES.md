# 用户故事文档 (User Stories)
## 分布式轻量级调度系统 - Distributed Lite Scheduler V1

---

## 📋 文档说明

本文档定义了分布式调度系统的核心用户故事，按业务优先级排序。每个用户故事包含：
- **角色定义**：谁需要这个功能
- **功能描述**：具体要实现什么
- **业务价值**：为什么需要这个功能
- **验收标准**：如何验证功能正确实现
- **涉及实体**：数据库表和关键字段
- **依赖关系**：前置用户故事

---

## 🎯 用户故事优先级概览

| 优先级 | 用户故事ID | 功能模块 | 核心价值 |
|--------|-----------|---------|----------|
| P0 | US-1 | 租户与成员管理 | 多租户隔离基础 |
| P0 | US-2 | 项目空间管理 | 任务组织基础 |
| P0 | US-3 | 任务定义与配置 | 调度核心元数据 |
| P1 | US-4 | 任务依赖(DAG) | 工作流编排能力 |
| P1 | US-5 | 任务实例执行 | 调度核心功能 |
| P1 | US-6 | 工作流定义 | 批量调度能力 |
| P0 | US-7 | 调度器执行闭环 | 系统运行核心 |
| P1 | US-8 | 执行日志与排障 | 可观测性基础 |
| P1 | US-9 | 资源节点与配额 | 资源管理能力 |
| P2 | US-10 | 监控与告警 | 运维保障能力 |

---

## 📖 详细用户故事

### US-1: 租户与成员管理
**优先级**: P0 - 必须  
**用户角色**: 租户管理员 (Tenant Administrator)  
**依赖**: 无

#### 用户故事
> **作为** 租户管理员  
> **我要** 创建租户并邀请用户、分配角色（OWNER/ADMIN/MEMBER/GUEST）  
> **以便** 实现多人协作且权限隔离，保证不同团队的数据安全

#### 业务价值
- ✅ 支持SaaS多租户模式
- ✅ 实现团队级权限隔离
- ✅ 灵活的权限分级（4级角色）

#### 验收标准 (Acceptance Criteria)
1. **租户创建**
   - [ ] 可以创建租户，指定 `tenant_name` 和 `tenant_code`（全局唯一）
   - [ ] `tenant_code` 必须符合规范（小写字母+数字+下划线，3-50字符）
   - [ ] 创建时自动将创建者设为 OWNER

2. **成员管理**
   - [ ] 可以邀请用户加入租户，指定角色：OWNER/ADMIN/MEMBER/GUEST
   - [ ] `(tenant_id, user_id)` 唯一约束生效（一个用户在同一租户只能有一个角色）
   - [ ] 同一用户可以在不同租户有不同角色（多租户支持）

3. **权限验证**
   - [ ] OWNER 可以删除租户
   - [ ] ADMIN 可以邀请/移除 MEMBER 和 GUEST
   - [ ] MEMBER 只能查看租户信息
   - [ ] GUEST 只有只读权限

4. **数据一致性**
   - [ ] 删除租户时，级联删除或禁止删除（根据业务规则）
   - [ ] 租户成员关系可以查询和审计

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `user` | id, username, email, status | 用户基础信息 |
| `tenant` | id, tenant_code, owner_user_id | 租户主表 |
| `tenant_member` | tenant_id, user_id, role | 租户成员关系表 |

#### 关键SQL约束
```sql
-- tenant_member 表唯一约束
UNIQUE KEY `uk_tenant_user` (`tenant_id`, `user_id`)

-- tenant_code 全局唯一
UNIQUE KEY `uk_tenant_code` (`tenant_code`)
```

#### 测试用例示例
```sql
-- 测试用例1: 同一用户在不同租户有不同角色
INSERT INTO tenant_member (tenant_id, user_id, role) VALUES 
  (1, 100, 'OWNER'),   -- 用户100在租户1是所有者
  (2, 100, 'MEMBER');  -- 用户100在租户2是普通成员

-- 测试用例2: 唯一约束验证（应该失败）
INSERT INTO tenant_member (tenant_id, user_id, role) VALUES 
  (1, 100, 'ADMIN');  -- 违反uk_tenant_user约束
```

---

### US-2: 项目空间管理
**优先级**: P0 - 必须  
**用户角色**: 项目成员 (Project Member)  
**依赖**: US-1 (租户与成员管理)

#### 用户故事
> **作为** 项目成员  
> **我要** 在租户下创建/维护项目（名称、编码、描述）  
> **以便** 实现任务与工作流的逻辑隔离和组织管理

#### 业务价值
- ✅ 按项目组织任务，避免混乱
- ✅ 支持多项目并行开发
- ✅ 实现租户→项目→任务的三级隔离

#### 验收标准 (Acceptance Criteria)
1. **项目创建**
   - [ ] 可以在指定租户下创建项目
   - [ ] `(tenant_id, project_code)` 唯一约束生效
   - [ ] 支持项目描述和扩展配置（extra_config JSON字段）

2. **项目查询**
   - [ ] 可以列出某租户下的所有项目
   - [ ] 支持按状态过滤（status=1正常，status=0禁用）
   - [ ] 支持软删除（deleted=0可见，deleted=1隐藏）

3. **项目限制**
   - [ ] 租户创建项目数不超过 `tenant.max_projects` 限制
   - [ ] 超出限制时抛出业务异常

4. **权限控制**
   - [ ] OWNER/ADMIN 可以创建/修改/删除项目
   - [ ] MEMBER 可以查看和使用项目
   - [ ] GUEST 只能查看项目信息

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `tenant` | id, max_projects | 租户主表（含项目限制） |
| `project` | id, tenant_id, project_code, creator_user_id | 项目主表 |

#### 关键SQL约束
```sql
-- project_code 在租户内唯一
UNIQUE KEY `uk_tenant_code` (`tenant_id`, `project_code`)
```

#### 测试用例示例
```sql
-- 测试用例1: 租户下项目编码唯一
INSERT INTO project (tenant_id, project_code, project_name) VALUES 
  (1, 'data-etl', 'Data ETL Project'),
  (1, 'data-etl', 'Another ETL');  -- 应该失败

-- 测试用例2: 不同租户可以有相同project_code
INSERT INTO project (tenant_id, project_code, project_name) VALUES 
  (1, 'common', 'Tenant1 Common'),
  (2, 'common', 'Tenant2 Common');  -- 应该成功
```

---

### US-3: 任务定义与资源配置
**优先级**: P0 - 必须  
**用户角色**: 项目成员 (Project Member)  
**依赖**: US-2 (项目空间管理)

#### 用户故事
> **作为** 项目成员  
> **我要** 在项目中创建任务定义，配置执行类型、资源需求  
> **以便** 调度器能够正确执行任务并分配合适的资源

#### 业务价值
- ✅ 定义任务执行逻辑（Shell/Python/Docker等）
- ✅ 声明资源需求（CPU/Memory/GPU）
- ✅ 支持版本控制和审计（updated_at、version字段）

#### 验收标准 (Acceptance Criteria)
1. **任务创建**
   - [ ] 可以在项目下创建任务定义
   - [ ] `(project_id, task_code)` 唯一约束生效
   - [ ] 必填字段：task_name, task_type, executor_config

2. **执行类型支持**
   - [ ] 支持 task_type: SHELL/PYTHON/DOCKER/HTTP/SQL
   - [ ] executor_config 存储执行细节（JSON格式）
     - 示例：`{"script": "python train.py", "timeout": 3600}`

3. **资源需求声明**
   - [ ] resource_require 字段存储资源需求（JSON格式）
     - 示例：`{"cpu": 2, "memory": "4Gi", "gpu": 1}`
   - [ ] 支持自定义资源标签（node_selector）

4. **版本控制**
   - [ ] 每次更新任务配置，`updated_at` 自动更新
   - [ ] 支持乐观锁版本控制（version字段）

5. **配置验证**
   - [ ] executor_config 必须是合法的JSON
   - [ ] resource_require 必须是合法的JSON
   - [ ] timeout 必须大于0

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `project` | id, tenant_id | 项目主表 |
| `task` | id, project_id, task_code, task_type, executor_config, resource_require | 任务定义表 |

#### 关键字段说明
| 字段 | 类型 | 说明 | 示例 |
|-----|------|------|------|
| task_type | VARCHAR(20) | 执行器类型 | SHELL/PYTHON/DOCKER |
| executor_config | JSON | 执行器配置 | `{"script": "echo hello"}` |
| resource_require | JSON | 资源需求 | `{"cpu": 2, "memory": "4Gi"}` |
| timeout | INT | 超时时间(秒) | 3600 |
| retry_times | INT | 重试次数 | 3 |
| retry_interval | INT | 重试间隔(秒) | 60 |

#### 测试用例示例
```sql
-- 测试用例1: 创建Shell类型任务
INSERT INTO task (project_id, task_code, task_name, task_type, executor_config, resource_require)
VALUES (
  1, 
  'backup-db', 
  'Database Backup Task',
  'SHELL',
  '{"script": "mysqldump -u root mydb > backup.sql", "timeout": 1800}',
  '{"cpu": 1, "memory": "2Gi"}'
);

-- 测试用例2: 创建需要GPU的Python任务
INSERT INTO task (project_id, task_code, task_name, task_type, executor_config, resource_require)
VALUES (
  1,
  'train-model',
  'Model Training Task',
  'PYTHON',
  '{"script": "python train.py --epochs 100", "python_version": "3.9"}',
  '{"cpu": 4, "memory": "16Gi", "gpu": 1, "gpu_type": "nvidia-v100"}'
);
```

---

### US-4: 任务依赖配置 (DAG)
**优先级**: P1 - 重要  
**用户角色**: 项目成员 (Project Member)  
**依赖**: US-3 (任务定义与资源配置)

#### 用户故事
> **作为** 项目成员  
> **我要** 为任务配置依赖关系（父任务、子任务、依赖类型）  
> **以便** 构建DAG工作流，按依赖顺序自动调度任务

#### 业务价值
- ✅ 支持复杂工作流编排（ETL Pipeline）
- ✅ 自动处理任务依赖关系
- ✅ 支持条件依赖（成功/失败/完成）

#### 验收标准 (Acceptance Criteria)
1. **依赖关系创建**
   - [ ] 可以创建任务依赖：`(parent_task_id, child_task_id)`
   - [ ] `(parent_task_id, child_task_id)` 唯一约束生效
   - [ ] 支持依赖类型：SUCCESS（父任务成功）/FAILED（父任务失败）/FINISHED（父任务完成）

2. **DAG合法性验证**
   - [ ] 禁止自环：task A 不能依赖自己
   - [ ] 禁止循环依赖：task A → B → C → A（应用层检测）
   - [ ] 拓扑排序验证：创建依赖前检查是否形成环

3. **依赖解析**
   - [ ] 调度器能正确解析依赖关系
   - [ ] 只有所有父任务满足依赖条件时，子任务才进入可执行队列
   - [ ] 父任务失败时，根据依赖类型决定子任务是否执行

4. **跨项目依赖**
   - [ ] 支持同一租户内跨项目的任务依赖（可选）
   - [ ] 不同租户的任务不能互相依赖

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `task` | id, project_id, task_code | 任务定义表 |
| `task_dependency` | parent_task_id, child_task_id, dependency_type | 任务依赖关系表 |

#### 依赖类型说明
| 依赖类型 | 说明 | 使用场景 |
|---------|------|----------|
| SUCCESS | 父任务执行成功后才执行子任务 | 正常流程：数据清洗 → 数据加载 |
| FAILED | 父任务执行失败后才执行子任务 | 异常处理：任务失败 → 发送告警 |
| FINISHED | 父任务完成（成功或失败）后执行子任务 | 清理操作：训练完成 → 清理临时文件 |

#### 测试用例示例
```sql
-- 测试用例1: 构建简单的ETL Pipeline
-- DAG: extract → transform → load
INSERT INTO task_dependency (parent_task_id, child_task_id, dependency_type) VALUES
  (1, 2, 'SUCCESS'),  -- extract成功后执行transform
  (2, 3, 'SUCCESS');  -- transform成功后执行load

-- 测试用例2: 带失败处理的DAG
-- DAG: main_task → [success_notify | error_notify]
INSERT INTO task_dependency (parent_task_id, child_task_id, dependency_type) VALUES
  (10, 11, 'SUCCESS'),  -- 成功后发送成功通知
  (10, 12, 'FAILED');   -- 失败后发送告警

-- 测试用例3: 自环检测（应该失败）
INSERT INTO task_dependency (parent_task_id, child_task_id, dependency_type) VALUES
  (5, 5, 'SUCCESS');  -- 任务不能依赖自己
```

#### DAG可视化示例
```
案例：数据处理Pipeline

extract_data (Task 1)
    │ (SUCCESS)
    ▼
transform_data (Task 2)
    │ (SUCCESS)
    ├──────────┐
    ▼          ▼
load_to_db   generate_report
(Task 3)     (Task 4)
    │            │
    └─────┬──────┘
          ▼
    cleanup_temp_files (Task 5)
    (FINISHED - 无论成功失败都清理)
```

---

### US-5: 提交任务实例
**优先级**: P1 - 重要  
**用户角色**: 项目成员 / 系统调度器  
**依赖**: US-3 (任务定义与资源配置), US-4 (任务依赖)

#### 用户故事
> **作为** 项目成员或系统调度器  
> **我要** 手动触发任务执行或按计划自动生成任务实例  
> **以便** 得到一条可追踪的TaskInstance记录

#### 业务价值
- ✅ 支持手动触发和定时触发
- ✅ 实例与定义分离（支持重跑和历史追踪）
- ✅ 唯一的instance_code实现幂等性

#### 验收标准 (Acceptance Criteria)
1. **任务实例创建**
   - [ ] 可以手动提交任务实例（API触发）
   - [ ] 系统可以根据调度计划自动生成实例
   - [ ] `instance_code` 全局唯一（格式：`{task_code}_{timestamp}_{random}`）

2. **初始状态**
   - [ ] 新创建的实例初始状态为 `PENDING`
   - [ ] 记录 `submit_time` 和 `submit_user_id`

3. **关联关系**
   - [ ] 实例关联到任务定义：`task_id`
   - [ ] 可选关联到工作流实例：`workflow_instance_id`（批量调度场景）

4. **幂等性保证**
   - [ ] 相同 `instance_code` 重复提交时，返回已存在的实例（不创建新实例）
   - [ ] 使用数据库唯一约束保证

5. **参数传递**
   - [ ] 支持运行时参数覆盖（instance_params JSON字段）
   - [ ] 示例：`{"input_file": "/data/2024-04-01.csv", "output_dir": "/output"}`

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `task` | id, task_code, task_type | 任务定义表 |
| `task_instance` | id, task_id, instance_code, status, submit_time | 任务实例表 |
| `workflow_instance` | id, workflow_id | 工作流实例表（可选） |

#### 实例状态流转
```
PENDING (待调度)
  ↓
WAITING (等待依赖)
  ↓
READY (可执行)
  ↓
RUNNING (执行中)
  ↓
┌─────────┬─────────┐
SUCCESS   FAILED    TIMEOUT
(成功)    (失败)    (超时)
```

#### 测试用例示例
```sql
-- 测试用例1: 手动提交任务实例
INSERT INTO task_instance (
  task_id, 
  instance_code, 
  status, 
  submit_user_id, 
  instance_params
) VALUES (
  1,
  'backup-db_20260403132400_a1b2c3',
  'PENDING',
  100,
  '{"backup_type": "full", "compress": true}'
);

-- 测试用例2: 工作流批量生成实例
INSERT INTO workflow_instance (workflow_id, instance_code, status) 
VALUES (1, 'daily-etl_20260403', 'RUNNING');

INSERT INTO task_instance (task_id, instance_code, status, workflow_instance_id) VALUES
  (1, 'extract_20260403_001', 'READY', 1),
  (2, 'transform_20260403_001', 'PENDING', 1),
  (3, 'load_20260403_001', 'PENDING', 1);
```

---

### US-6: 工作流定义与调度
**优先级**: P1 - 重要  
**用户角色**: 项目成员 (Project Member)  
**依赖**: US-4 (任务依赖), US-5 (任务实例)

#### 用户故事
> **作为** 项目成员  
> **我要** 定义工作流（DAG结构、调度周期）并隶属于项目  
> **以便** 按DAG + Cron周期批量产生任务实例

#### 业务价值
- ✅ 支持定时调度（每天/每周/每月）
- ✅ 一次定义，周期执行
- ✅ 批量管理一组相关任务

#### 验收标准 (Acceptance Criteria)
1. **工作流创建**
   - [ ] 可以在项目下创建工作流定义
   - [ ] `(project_id, workflow_code)` 唯一约束生效
   - [ ] 必填字段：workflow_name, dag_json, schedule_cron

2. **DAG定义**
   - [ ] dag_json 包含任务列表和依赖关系
   - [ ] 示例结构：
     ```json
     {
       "tasks": [
         {"task_code": "extract", "task_id": 1},
         {"task_code": "transform", "task_id": 2}
       ],
       "dependencies": [
         {"parent": "extract", "child": "transform", "type": "SUCCESS"}
       ]
     }
     ```

3. **调度周期配置**
   - [ ] schedule_cron 支持标准Cron表达式
   - [ ] 示例：`0 2 * * *`（每天凌晨2点）
   - [ ] 应用层验证Cron表达式合法性

4. **工作流实例生成**
   - [ ] 调度器按Cron计划自动创建 `workflow_instance`
   - [ ] 每个实例生成唯一的 `instance_code`
   - [ ] 实例创建时批量生成所有任务实例

5. **状态管理**
   - [ ] workflow 支持启用/禁用（status字段）
   - [ ] 禁用后不再生成新实例

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `project` | id, tenant_id | 项目主表 |
| `workflow` | id, project_id, workflow_code, dag_json, schedule_cron | 工作流定义表 |
| `workflow_instance` | id, workflow_id, instance_code, status | 工作流实例表 |

#### Cron表达式示例
| 表达式 | 说明 |
|--------|------|
| `0 0 * * *` | 每天凌晨12点 |
| `0 2 * * *` | 每天凌晨2点 |
| `0 */6 * * *` | 每6小时一次 |
| `0 0 * * 1` | 每周一凌晨12点 |
| `0 0 1 * *` | 每月1号凌晨12点 |

#### 测试用例示例
```sql
-- 测试用例1: 创建每日ETL工作流
INSERT INTO workflow (
  project_id, 
  workflow_code, 
  workflow_name,
  dag_json,
  schedule_cron,
  status
) VALUES (
  1,
  'daily-etl',
  'Daily ETL Pipeline',
  '{
    "tasks": [
      {"task_id": 1, "task_code": "extract"},
      {"task_id": 2, "task_code": "transform"},
      {"task_id": 3, "task_code": "load"}
    ],
    "dependencies": [
      {"parent": "extract", "child": "transform", "type": "SUCCESS"},
      {"parent": "transform", "child": "load", "type": "SUCCESS"}
    ]
  }',
  '0 2 * * *',  -- 每天凌晨2点
  1
);
```

---

### US-7: 调度器执行闭环
**优先级**: P0 - 必须  
**用户角色**: 系统调度器 (System Scheduler)  
**依赖**: US-4 (任务依赖), US-5 (任务实例), US-9 (资源节点)

#### 用户故事
> **作为** 系统调度器  
> **我要** 从PENDING实例中挑选满足依赖和资源条件的任务，绑定节点并推进状态  
> **以便** 任务真正执行且不破坏依赖语义

#### 业务价值
- ✅ 自动化任务调度，无需人工干预
- ✅ 保证依赖关系正确性
- ✅ 资源感知调度（CPU/GPU限制）

#### 验收标准 (Acceptance Criteria)
1. **依赖检查**
   - [ ] 调度前检查所有父任务是否满足依赖条件
   - [ ] SUCCESS依赖：所有父任务状态为SUCCESS
   - [ ] FAILED依赖：所有父任务状态为FAILED
   - [ ] FINISHED依赖：所有父任务状态为SUCCESS或FAILED

2. **资源分配**
   - [ ] 根据 `task.resource_require` 查找可用的 `resource_node`
   - [ ] 节点可用资源充足时才分配：
     - `node.available_cpu >= required_cpu`
     - `node.available_memory >= required_memory`
     - `node.available_gpu >= required_gpu`
   - [ ] 分配后写入 `task_instance.resource_node_id`

3. **状态流转**
   - [ ] PENDING → READY：依赖满足且有资源
   - [ ] READY → RUNNING：开始执行，记录 `start_time`
   - [ ] RUNNING → SUCCESS/FAILED：执行完成，记录 `end_time` 和 `duration_ms`

4. **并发控制**
   - [ ] 使用乐观锁（version字段）防止重复调度
   - [ ] 使用分布式锁（Redis）避免多调度器冲突

5. **失败重试**
   - [ ] 任务失败时，根据 `task.retry_times` 自动重试
   - [ ] 重试间隔遵循 `task.retry_interval`
   - [ ] 超过重试次数后最终标记为FAILED

6. **超时处理**
   - [ ] 任务运行时间超过 `task.timeout` 时自动终止
   - [ ] 状态设置为TIMEOUT

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `task` | id, resource_require, timeout, retry_times | 任务定义表 |
| `task_instance` | id, status, resource_node_id, start_time, end_time | 任务实例表 |
| `task_dependency` | parent_task_id, child_task_id, dependency_type | 任务依赖表 |
| `resource_node` | id, available_cpu, available_memory, available_gpu | 资源节点表 |

#### 调度算法伪代码
```java
// 调度主循环
while (true) {
    // 1. 获取所有PENDING实例
    List<TaskInstance> pendingInstances = selectPendingInstances();
    
    for (TaskInstance instance : pendingInstances) {
        // 2. 检查依赖是否满足
        if (!checkDependencies(instance)) {
            continue;
        }
        
        // 3. 查找可用资源节点
        ResourceNode node = findAvailableNode(instance.getTask().getResourceRequire());
        if (node == null) {
            continue;  // 资源不足，等待下次调度
        }
        
        // 4. 分配资源并更新状态
        allocateResource(instance, node);
        updateInstanceStatus(instance, "RUNNING");
        
        // 5. 提交到执行器
        submitToExecutor(instance, node);
    }
    
    Thread.sleep(5000);  // 每5秒调度一次
}
```

#### 测试用例示例
```sql
-- 测试用例1: 依赖检查
-- Task B 依赖 Task A (SUCCESS类型)
SELECT ti.* 
FROM task_instance ti
JOIN task_dependency td ON td.child_task_id = ti.task_id
JOIN task_instance parent_ti ON parent_ti.task_id = td.parent_task_id 
  AND parent_ti.workflow_instance_id = ti.workflow_instance_id
WHERE ti.status = 'PENDING'
  AND td.dependency_type = 'SUCCESS'
  AND parent_ti.status != 'SUCCESS';
-- 应该返回空（说明依赖未满足）

-- 测试用例2: 资源分配
SELECT * FROM resource_node
WHERE status = 'ONLINE'
  AND available_cpu >= 2
  AND available_memory >= 4096
  AND available_gpu >= 1
ORDER BY available_cpu DESC
LIMIT 1;
-- 返回满足条件的最优节点
```

---

### US-8: 执行日志与排障
**优先级**: P1 - 重要  
**用户角色**: 项目成员 / 运维人员  
**依赖**: US-5 (任务实例), US-7 (调度器执行)

#### 用户故事
> **作为** 项目成员或运维人员  
> **我要** 查看某次任务实例的执行日志（级别、内容、时间戳）  
> **以便** 快速定位失败原因和排查问题

#### 业务价值
- ✅ 快速故障定位
- ✅ 完整的执行轨迹
- ✅ 支持多级日志（INFO/WARN/ERROR）

#### 验收标准 (Acceptance Criteria)
1. **日志记录**
   - [ ] 执行器在任务执行过程中实时写入日志
   - [ ] 每条日志包含：level, message, log_time
   - [ ] 支持日志级别：INFO/WARN/ERROR/DEBUG

2. **日志查询**
   - [ ] 可以按 `task_instance_id` 查询所有日志
   - [ ] 支持分页查询（避免大日志OOM）
   - [ ] 支持按日志级别过滤

3. **日志存储**
   - [ ] 小日志（< 10MB）存储在数据库 `execution_log` 表
   - [ ] 大日志（> 10MB）存储在对象存储（MinIO/OSS），数据库仅保存链接

4. **日志生命周期**
   - [ ] 日志与实例生命周期一致
   - [ ] 实例删除时，级联删除日志（或根据策略保留）

5. **实时日志流**
   - [ ] 支持WebSocket实时推送日志（可选）
   - [ ] 前端可以订阅某个实例的日志流

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `task_instance` | id, instance_code, status | 任务实例表 |
| `execution_log` | id, task_instance_id, level, message, log_time | 执行日志表 |

#### 日志级别说明
| 级别 | 说明 | 使用场景 |
|------|------|----------|
| DEBUG | 调试信息 | 详细的运行时变量 |
| INFO | 常规信息 | 任务启动、完成等关键步骤 |
| WARN | 警告信息 | 重试、性能警告 |
| ERROR | 错误信息 | 异常堆栈、失败原因 |

#### 测试用例示例
```sql
-- 测试用例1: 查询某实例的所有ERROR日志
SELECT * FROM execution_log
WHERE task_instance_id = 12345
  AND level = 'ERROR'
ORDER BY log_time DESC;

-- 测试用例2: 分页查询日志
SELECT * FROM execution_log
WHERE task_instance_id = 12345
ORDER BY log_time ASC
LIMIT 100 OFFSET 0;

-- 测试用例3: 统计各级别日志数量
SELECT level, COUNT(*) as log_count
FROM execution_log
WHERE task_instance_id = 12345
GROUP BY level;
```

#### 日志示例
```json
[
  {
    "level": "INFO",
    "message": "Task started: backup-db_20260403132400_a1b2c3",
    "log_time": "2026-04-03 13:24:00"
  },
  {
    "level": "INFO",
    "message": "Connecting to database: mysql://10.0.0.1:3306/mydb",
    "log_time": "2026-04-03 13:24:01"
  },
  {
    "level": "WARN",
    "message": "Connection timeout, retrying (attempt 1/3)...",
    "log_time": "2026-04-03 13:24:31"
  },
  {
    "level": "ERROR",
    "message": "Connection failed: java.net.ConnectException: Connection refused",
    "log_time": "2026-04-03 13:25:01"
  }
]
```

---

### US-9: 资源节点与配额管理
**优先级**: P1 - 重要  
**用户角色**: 租户管理员 / 平台运维  
**依赖**: US-1 (租户管理), US-7 (调度器执行)

#### 用户故事
> **作为** 租户管理员，我要查看租户资源配额（CPU/Memory已用/上限）  
> **作为** 平台运维，我要维护资源节点（容量、状态、可用资源）  
> **以便** 实现资源隔离和防止资源超卖

#### 业务价值
- ✅ 多租户资源隔离
- ✅ 防止资源滥用
- ✅ 资源使用透明化

#### 验收标准 (Acceptance Criteria)
1. **资源配额管理**
   - [ ] 每个租户有独立的 `resource_quota` 记录
   - [ ] 配额包含：CPU、Memory、GPU的 已用/上限
   - [ ] 提交任务时检查：`used + required <= limit`

2. **资源节点管理**
   - [ ] 运维可以添加/下线资源节点
   - [ ] 节点状态：ONLINE/OFFLINE/MAINTAINING
   - [ ] OFFLINE 节点不参与任务调度

3. **资源分配与回收**
   - [ ] 任务调度时，扣减节点可用资源和租户配额
   - [ ] 任务完成时，释放节点资源和租户配额
   - [ ] 使用乐观锁（version字段）保证并发安全

4. **配额预警**
   - [ ] 租户配额使用超过80%时发送预警
   - [ ] 配额耗尽时禁止新任务提交

5. **节点健康检查**
   - [ ] 定期心跳检测节点状态
   - [ ] 节点失联超过阈值时自动标记为OFFLINE
   - [ ] 失联节点上的运行任务自动重调度

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `tenant` | id, tenant_code | 租户主表 |
| `resource_quota` | tenant_id, cpu_used, cpu_limit, memory_used, memory_limit | 资源配额表 |
| `resource_node` | id, node_ip, total_cpu, available_cpu, status | 资源节点表 |
| `task_instance` | id, resource_node_id | 任务实例表 |

#### 资源分配算法伪代码
```java
// 分配资源
public boolean allocateResource(TaskInstance instance, ResourceNode node) {
    Task task = instance.getTask();
    Tenant tenant = task.getProject().getTenant();
    
    // 1. 检查租户配额
    ResourceQuota quota = getQuota(tenant.getId());
    if (!quota.hasEnough(task.getResourceRequire())) {
        log.warn("Tenant {} quota insufficient", tenant.getTenantCode());
        return false;
    }
    
    // 2. 检查节点资源
    if (!node.hasEnough(task.getResourceRequire())) {
        return false;
    }
    
    // 3. 扣减资源（使用乐观锁）
    boolean success = transactional(() -> {
        quota.allocate(task.getResourceRequire());  // version++
        node.allocate(task.getResourceRequire());   // version++
        instance.setResourceNodeId(node.getId());
    });
    
    return success;
}

// 释放资源
public void releaseResource(TaskInstance instance) {
    ResourceNode node = instance.getResourceNode();
    ResourceQuota quota = getQuota(instance.getTenantId());
    
    transactional(() -> {
        quota.release(instance.getResourceUsed());
        node.release(instance.getResourceUsed());
    });
}
```

#### 测试用例示例
```sql
-- 测试用例1: 检查租户配额是否充足
SELECT 
  t.tenant_code,
  rq.cpu_used,
  rq.cpu_limit,
  (rq.cpu_limit - rq.cpu_used) as cpu_available,
  (rq.cpu_limit - rq.cpu_used) >= 4 as can_allocate_4_cpu
FROM tenant t
JOIN resource_quota rq ON t.id = rq.tenant_id
WHERE t.id = 1;

-- 测试用例2: 查找可用资源节点
SELECT * FROM resource_node
WHERE status = 'ONLINE'
  AND available_cpu >= 4
  AND available_memory >= 8192
  AND available_gpu >= 1
ORDER BY available_cpu DESC
LIMIT 1;

-- 测试用例3: 资源分配（乐观锁）
UPDATE resource_quota
SET cpu_used = cpu_used + 4,
    memory_used = memory_used + 8192,
    version = version + 1
WHERE tenant_id = 1
  AND version = 5  -- 乐观锁版本检查
  AND (cpu_limit - cpu_used) >= 4
  AND (memory_limit - memory_used) >= 8192;
-- 如果影响行数=0，说明并发冲突或配额不足
```

---

### US-10: 监控与告警
**优先级**: P2 - 次要  
**用户角色**: 租户管理员 / 运维人员  
**依赖**: US-1 (租户管理), US-5 (任务实例)

#### 用户故事
> **作为** 租户管理员  
> **我要** 配置告警规则（监控对象、触发条件、通知方式）  
> **以便** 及时发现任务失败、资源不足等异常情况

#### 业务价值
- ✅ 主动发现问题（而非被动等待用户反馈）
- ✅ 减少故障恢复时间（MTTR）
- ✅ 支持多种告警渠道（邮件/钉钉/企业微信）

#### 验收标准 (Acceptance Criteria)
1. **告警规则配置**
   - [ ] 可以创建告警规则，指定：
     - 监控对象类型：PROJECT/TASK/WORKFLOW
     - 监控对象ID：target_id
     - 触发条件：rule_expression（JSON）
     - 通知渠道：notify_channels（JSON）

2. **告警条件支持**
   - [ ] 任务失败率：`failure_rate > 50%`
   - [ ] 任务超时：`duration > timeout * 1.2`
   - [ ] 资源使用率：`cpu_usage > 80%`
   - [ ] 任务积压：`pending_count > 100`

3. **告警触发**
   - [ ] 调度器定期（如每分钟）检查告警规则
   - [ ] 条件满足时，记录 `metric_snapshot` 并发送通知
   - [ ] 支持告警静默期（避免重复告警）

4. **性能快照**
   - [ ] `metric_snapshot` 记录触发时的详细指标
   - [ ] 包含：对象信息、指标值、触发时间

5. **通知渠道**
   - [ ] 支持邮件通知（SMTP）
   - [ ] 支持钉钉机器人（Webhook）
   - [ ] 支持企业微信机器人（Webhook）

#### 涉及实体 (Database Entities)
| 表名 | 关键字段 | 说明 |
|-----|---------|------|
| `tenant` | id, tenant_code | 租户主表 |
| `alert_rule` | id, tenant_id, target_type, target_id, rule_expression | 告警规则表 |
| `metric_snapshot` | id, target_type, target_id, metric_data, captured_at | 性能快照表 |

#### 告警规则示例
```json
{
  "rule_name": "Task Failure Alert",
  "target_type": "TASK",
  "target_id": 123,
  "rule_expression": {
    "metric": "failure_rate",
    "operator": ">",
    "threshold": 0.5,
    "time_window": "1h"
  },
  "notify_channels": {
    "email": ["admin@example.com"],
    "dingtalk": ["https://oapi.dingtalk.com/robot/send?access_token=xxx"]
  },
  "silence_period": 3600  // 1小时内不重复告警
}
```

#### 测试用例示例
```sql
-- 测试用例1: 创建任务失败率告警
INSERT INTO alert_rule (
  tenant_id,
  rule_name,
  target_type,
  target_id,
  rule_expression,
  notify_channels,
  status
) VALUES (
  1,
  'ETL Task Failure Alert',
  'TASK',
  10,
  '{
    "metric": "failure_rate",
    "operator": ">",
    "threshold": 0.5,
    "time_window": "1h"
  }',
  '{
    "email": ["ops@example.com"],
    "dingtalk": ["https://oapi.dingtalk.com/robot/send?access_token=xxx"]
  }',
  1
);

-- 测试用例2: 查询最近1小时的任务失败率
SELECT 
  COUNT(*) FILTER (WHERE status = 'FAILED') as failed_count,
  COUNT(*) as total_count,
  ROUND(
    COUNT(*) FILTER (WHERE status = 'FAILED')::NUMERIC / COUNT(*), 
    2
  ) as failure_rate
FROM task_instance
WHERE task_id = 10
  AND start_time >= NOW() - INTERVAL '1 hour';

-- 测试用例3: 记录性能快照
INSERT INTO metric_snapshot (
  target_type,
  target_id,
  metric_name,
  metric_value,
  metric_data
) VALUES (
  'TASK',
  10,
  'failure_rate',
  0.65,
  '{
    "failed_count": 13,
    "total_count": 20,
    "time_window": "1h",
    "alert_rule_id": 5
  }'
);
```

#### 告警通知示例（钉钉）
```json
{
  "msgtype": "markdown",
  "markdown": {
    "title": "【告警】任务失败率超阈值",
    "text": "### 🚨 任务失败率告警\n\n" +
            "- **租户**: demo-tenant\n" +
            "- **项目**: data-platform\n" +
            "- **任务**: ETL-daily-sync\n" +
            "- **失败率**: 65% (13/20)\n" +
            "- **阈值**: 50%\n" +
            "- **时间窗口**: 最近1小时\n\n" +
            "[查看详情](https://scheduler.example.com/tasks/10)"
  }
}
```

---

## 📊 用户故事依赖关系图

```
US-1 (租户与成员)
  │
  ├─→ US-2 (项目空间)
  │     │
  │     ├─→ US-3 (任务定义)
  │     │     │
  │     │     ├─→ US-4 (任务依赖)
  │     │     │     │
  │     │     │     └─→ US-5 (任务实例)
  │     │     │           │
  │     │     │           ├─→ US-6 (工作流)
  │     │     │           │     │
  │     │     │           │     └─→ US-7 (调度执行) ←─┐
  │     │     │           │                           │
  │     │     │           └─→ US-8 (执行日志)         │
  │     │     │                                       │
  │     │     └─→ US-10 (监控告警)                     │
  │     │                                             │
  │     └─→ US-9 (资源管理) ──────────────────────────┘
  │
  └─→ US-9 (资源配额)
```

---

## 🎯 实施建议

### Phase 1: 基础设施 (2-3周)
- ✅ US-1: 租户与成员管理
- ✅ US-2: 项目空间管理
- ✅ US-3: 任务定义与资源配置

**交付物**: 基础的多租户框架 + 任务定义API

### Phase 2: 调度核心 (3-4周)
- ✅ US-4: 任务依赖(DAG)
- ✅ US-5: 任务实例执行
- ✅ US-7: 调度器执行闭环
- ✅ US-9: 资源节点与配额

**交付物**: 可运行的调度系统 + 资源管理

### Phase 3: 工作流与监控 (2-3周)
- ✅ US-6: 工作流定义与调度
- ✅ US-8: 执行日志与排障
- ✅ US-10: 监控与告警

**交付物**: 完整的生产级系统

---

## 📝 文档版本历史

| 版本 | 日期 | 修改内容 | 作者 |
|-----|------|---------|------|
| v1.0 | 2026-04-03 | 初始版本，定义10个核心用户故事 | System |

---

## 🔗 相关文档
- [数据库设计文档](./DATABASE_DESIGN.md)
- [项目计划文档](./PROJECT_PLAN.md)
- [任务分解文档](./TASK_BREAKDOWN.md)
