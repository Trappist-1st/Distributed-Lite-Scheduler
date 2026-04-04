# 核心业务逻辑文档 (Core Business Logic)
## 分布式轻量级调度系统 - Distributed Lite Scheduler V1

---

## 📋 文档说明

本文档详细描述系统的核心业务逻辑，不包含具体代码实现，而是聚焦于：
- **业务流程**：各功能模块的处理流程
- **业务规则**：约束条件和验证规则
- **状态流转**：各实体的状态变化逻辑
- **边界条件**：异常场景和边界处理
- **数据一致性**：并发控制和事务要求

每个业务模块独立描述，便于分工开发和代码实现。

---

## 目录

1. [用户与认证管理](#1-用户与认证管理)
2. [租户与成员管理](#2-租户与成员管理)
3. [项目空间管理](#3-项目空间管理)
4. [任务定义管理](#4-任务定义管理)
5. [任务依赖与DAG管理](#5-任务依赖与dag管理)
6. [任务实例执行](#6-任务实例执行)
7. [工作流管理](#7-工作流管理)
8. [调度器核心逻辑](#8-调度器核心逻辑)
9. [资源管理与调度](#9-资源管理与调度)
10. [执行日志管理](#10-执行日志管理)
11. [监控与告警](#11-监控与告警)
12. [分布式一致性保障](#12-分布式一致性保障)

---

## 1. 用户与认证管理

### 1.1 业务概述
管理系统用户的注册、登录、认证和授权，提供安全的身份验证机制。

### 1.2 用户注册流程

#### 输入参数
- 用户名（username）：3-50字符，仅支持字母、数字、下划线
- 邮箱（email）：标准邮箱格式
- 密码（password）：原始密码，8-32字符
- 昵称（nickname）：可选，1-50字符

#### 业务规则
1. **唯一性验证**
   - 用户名必须全局唯一（不区分大小写）
   - 邮箱必须全局唯一
   - 查询时忽略已删除用户（deleted=1）

2. **密码安全**
   - 使用bcrypt算法加密存储
   - 加盐强度建议为10-12
   - 不存储原始密码
   - 密码哈希存储在password_hash字段

3. **默认值设置**
   - status默认为1（正常状态）
   - deleted默认为0（未删除）
   - created_at和updated_at自动填充

4. **输入验证**
   - 用户名正则：`^[a-zA-Z0-9_]{3,50}$`
   - 邮箱正则：标准RFC5322格式
   - 密码强度：至少包含字母和数字

#### 异常场景
- 用户名已存在：返回"用户名已被注册"
- 邮箱已存在：返回"邮箱已被注册"
- 格式不合法：返回具体的格式错误信息

### 1.3 用户登录流程

#### 输入参数
- 登录凭证（credential）：用户名或邮箱
- 密码（password）：原始密码

#### 业务规则
1. **凭证识别**
   - 判断输入是邮箱还是用户名
   - 根据类型查询用户记录

2. **密码验证**
   - 使用bcrypt验证原始密码与数据库密码哈希
   - 验证失败次数限制（可选）：5次失败后锁定15分钟

3. **状态检查**
   - 检查用户状态（status）是否为正常（1）
   - 禁用用户（status=0）不允许登录
   - 已删除用户（deleted=1）不允许登录

4. **登录成功处理**
   - 更新last_login_time为当前时间
   - 更新last_login_ip为客户端IP
   - 生成JWT令牌（包含用户ID、用户名、角色信息）
   - 令牌有效期：7天（可配置）

#### 异常场景
- 用户不存在：返回"用户名或密码错误"（不暴露具体原因）
- 密码错误：返回"用户名或密码错误"
- 用户被禁用：返回"账户已被禁用，请联系管理员"
- 用户被删除：返回"用户名或密码错误"

### 1.4 权限验证流程

#### 验证时机
- 每次API请求都需要验证JWT令牌
- 敏感操作需要二次验证（如删除租户）

#### 验证规则
1. **令牌验证**
   - 检查令牌是否存在于HTTP Header（Authorization: Bearer {token}）
   - 验证令牌签名是否有效
   - 检查令牌是否过期
   - 解析用户ID和权限信息

2. **用户状态验证**
   - 查询用户当前状态
   - 如果用户被禁用或删除，拒绝请求

3. **权限验证**
   - 根据请求资源和操作类型判断所需权限
   - 验证用户在对应租户中的角色
   - 验证角色是否有权限执行该操作

#### 异常场景
- 令牌缺失：返回401 Unauthorized
- 令牌过期：返回401 Token Expired
- 权限不足：返回403 Forbidden

---

## 2. 租户与成员管理

### 2.1 业务概述
实现多租户SaaS模式，支持租户创建、成员管理、权限分级，保证数据隔离。

### 2.2 租户创建流程

#### 输入参数
- 租户名称（tenant_name）：1-100字符
- 租户编码（tenant_code）：3-50字符，小写字母+数字+下划线
- 租户描述（description）：可选
- 最大项目数（max_projects）：默认10
- 最大任务数（max_tasks）：默认1000

#### 业务规则
1. **唯一性验证**
   - tenant_code必须全局唯一
   - 建议格式：公司名-部门名，如"alibaba-data"

2. **自动初始化**
   - 创建者自动成为租户的OWNER
   - 在tenant_member表插入一条记录（user_id=创建者, role=OWNER）
   - 同时创建resource_quota记录，分配默认配额
   - 默认配额：CPU=10核，Memory=10GB，GPU=0

3. **默认值设置**
   - status默认为1（正常）
   - expire_time默认为NULL（永久有效）
   - created_at和updated_at自动填充

4. **输入验证**
   - tenant_code正则：`^[a-z0-9_]{3,50}$`
   - max_projects和max_tasks必须大于0

#### 数据一致性要求
- 租户创建和成员添加必须在同一事务中
- 租户创建和配额创建必须在同一事务中
- 任一步骤失败则全部回滚

#### 异常场景
- tenant_code已存在：返回"租户编码已存在"
- 格式不合法：返回具体的格式错误信息
- 事务失败：返回"租户创建失败，请重试"

### 2.3 成员邀请流程

#### 输入参数
- 租户ID（tenant_id）
- 用户ID或邮箱（user_id/email）
- 角色（role）：OWNER/ADMIN/MEMBER/GUEST

#### 前置条件
- 操作者必须是该租户的OWNER或ADMIN
- 被邀请用户必须已注册且状态正常

#### 业务规则
1. **权限验证**
   - OWNER可以邀请任何角色（包括OWNER）
   - ADMIN只能邀请MEMBER和GUEST
   - MEMBER和GUEST无权邀请

2. **唯一性约束**
   - (tenant_id, user_id)必须唯一
   - 同一用户在同一租户只能有一个角色
   - 如需修改角色，必须先删除再添加

3. **多租户支持**
   - 同一用户可以加入多个租户
   - 在不同租户可以有不同角色
   - 示例：用户A在租户1是OWNER，在租户2是MEMBER

4. **自动通知**
   - 成功添加后发送邀请通知（邮件或站内信）
   - 通知内容包含租户名称和角色信息

#### 异常场景
- 权限不足：返回"您没有权限邀请成员"
- 用户已在租户中：返回"该用户已是本租户成员"
- 用户不存在：返回"用户不存在"

### 2.4 成员角色变更流程

#### 输入参数
- 租户ID（tenant_id）
- 用户ID（user_id）
- 新角色（new_role）

#### 前置条件
- 操作者必须是OWNER

#### 业务规则
1. **OWNER特殊处理**
   - 租户必须至少有一个OWNER
   - 变更OWNER角色前，必须确保有其他OWNER存在
   - 或者同时指定新的OWNER

2. **角色降级限制**
   - OWNER可以降级为其他任意角色
   - ADMIN降级为MEMBER/GUEST需要OWNER审批

3. **更新操作**
   - 直接更新tenant_member表的role字段
   - updated_at自动更新

#### 异常场景
- 最后一个OWNER：返回"至少保留一个所有者"
- 权限不足：返回"只有所有者可以变更角色"

### 2.5 成员移除流程

#### 输入参数
- 租户ID（tenant_id）
- 用户ID（user_id）

#### 前置条件
- 操作者必须是OWNER或ADMIN
- ADMIN只能移除MEMBER和GUEST

#### 业务规则
1. **OWNER保护**
   - 不能移除最后一个OWNER
   - OWNER只能由其他OWNER移除

2. **级联检查**
   - 检查被移除用户是否是项目创建者
   - 检查是否有任务或工作流归属该用户
   - 根据策略决定是否允许移除或转移所有权

3. **删除操作**
   - 物理删除tenant_member记录
   - 不使用软删除

#### 异常场景
- 最后一个OWNER：返回"不能移除最后一个所有者"
- 权限不足：返回"您没有权限移除该成员"
- 有关联资源：返回"该用户名下有资源，请先转移"

### 2.6 租户删除流程

#### 输入参数
- 租户ID（tenant_id）

#### 前置条件
- 操作者必须是OWNER
- 需要二次确认（输入租户编码）

#### 业务规则
1. **级联检查**
   - 检查租户下是否有项目
   - 检查是否有运行中的任务实例
   - 检查是否有待调度的工作流

2. **删除策略（二选一）**
   - **策略A：硬约束** - 必须清空所有资源才能删除
   - **策略B：级联删除** - 自动删除所有关联资源

3. **软删除**
   - 设置deleted=1
   - 保留历史数据用于审计
   - 租户编码可以在一定时间后重用（如30天）

4. **资源释放**
   - 释放该租户占用的资源配额
   - 删除resource_quota记录

#### 数据一致性要求
- 整个删除过程必须在事务中
- 任一步骤失败则全部回滚

#### 异常场景
- 有运行中任务：返回"请先停止所有运行中的任务"
- 有未清空资源：返回"请先清空所有项目和任务"
- 权限不足：返回"只有所有者可以删除租户"

---

## 3. 项目空间管理

### 3.1 业务概述
在租户下创建逻辑隔离的项目空间，用于组织和管理任务、工作流。

### 3.2 项目创建流程

#### 输入参数
- 租户ID（tenant_id）
- 项目名称（project_name）：1-100字符
- 项目编码（project_code）：3-50字符
- 项目描述（description）：可选
- 扩展配置（extra_config）：可选JSON

#### 前置条件
- 操作者必须是租户的OWNER、ADMIN或MEMBER
- GUEST无权创建项目

#### 业务规则
1. **唯一性验证**
   - (tenant_id, project_code)必须唯一
   - 不同租户可以有相同的project_code

2. **配额检查**
   - 查询租户的max_projects限制
   - 统计当前租户下的项目数（deleted=0）
   - 如果达到上限，拒绝创建

3. **默认值设置**
   - status默认为1（正常）
   - creator_user_id记录创建者ID
   - created_at和updated_at自动填充

4. **输入验证**
   - project_code正则：`^[a-z0-9_-]{3,50}$`
   - extra_config必须是合法的JSON（如果提供）

#### 扩展配置示例
扩展配置可用于存储项目级别的自定义设置：
- 默认任务优先级
- 默认超时时间
- 默认重试次数
- 项目标签和分类
- 自定义环境变量

#### 异常场景
- project_code重复：返回"项目编码在该租户下已存在"
- 配额已满：返回"已达到最大项目数限制（{max_projects}）"
- 权限不足：返回"访客无权创建项目"

### 3.3 项目查询流程

#### 查询场景
1. **租户下所有项目** - 管理页面展示
2. **用户可见项目** - 根据用户角色过滤
3. **项目详情** - 包含统计信息

#### 业务规则
1. **权限过滤**
   - OWNER/ADMIN可见租户下所有项目
   - MEMBER只能看到自己创建的项目或被授权的项目
   - GUEST只能查看，无权限修改

2. **状态过滤**
   - 默认只显示status=1且deleted=0的项目
   - 可选显示已禁用项目（ADMIN权限）

3. **统计信息**
   - 项目下的任务总数
   - 项目下的工作流总数
   - 最近一次任务执行时间
   - 任务成功率统计

#### 返回字段
- 项目基础信息（id, name, code, description）
- 创建者信息（creator_user_id, creator_username）
- 时间信息（created_at, updated_at）
- 统计信息（task_count, workflow_count, last_run_time）

### 3.4 项目更新流程

#### 可更新字段
- project_name - 项目名称
- description - 项目描述
- extra_config - 扩展配置
- status - 项目状态（需要ADMIN权限）

#### 前置条件
- 操作者是项目创建者，或租户ADMIN/OWNER

#### 业务规则
1. **不可更新字段**
   - project_code不允许修改（唯一标识）
   - tenant_id不允许修改（不支持项目转移）
   - creator_user_id不允许修改

2. **状态变更**
   - 禁用项目（status=0）会影响下属任务的调度
   - 禁用项目时，自动暂停所有定时任务
   - 禁用项目时，不影响已在运行的任务实例

3. **版本控制**
   - updated_at自动更新
   - 重要变更记录审计日志

#### 异常场景
- 权限不足：返回"您没有权限修改该项目"
- 项目不存在：返回"项目不存在或已删除"

### 3.5 项目删除流程

#### 输入参数
- 项目ID（project_id）

#### 前置条件
- 操作者是项目创建者，或租户OWNER
- 需要二次确认

#### 业务规则
1. **级联检查**
   - 检查项目下是否有任务定义
   - 检查是否有运行中的任务实例
   - 检查是否有工作流定义

2. **删除策略**
   - **推荐策略**：软删除（deleted=1）
   - 保留历史数据用于审计
   - 相关任务和工作流同步软删除

3. **清理操作**
   - 设置项目及下属所有任务的deleted=1
   - 停止所有定时调度
   - 取消所有PENDING状态的任务实例

#### 数据一致性要求
- 整个删除过程必须在事务中
- 失败则回滚

#### 异常场景
- 有运行中任务：返回"请先停止所有运行中的任务"
- 有未清空资源：返回"请先删除项目下所有任务"
- 权限不足：返回"只有项目创建者或租户所有者可以删除项目"

---

## 4. 任务定义管理

### 4.1 业务概述
定义可复用的任务模板，包含执行逻辑、资源需求、调度配置等元数据。

### 4.2 任务创建流程

#### 输入参数
- 项目ID（project_id）
- 任务名称（task_name）：1-100字符
- 任务编码（task_code）：3-50字符
- 任务类型（task_type）：SHELL/PYTHON/DOCKER/K8S_JOB
- 执行器配置（executor_config）：JSON格式
- 调度类型（schedule_type）：MANUAL/CRON/DEPENDENCY
- Cron表达式（cron_expression）：调度类型为CRON时必填
- 超时时间（timeout_seconds）：默认3600秒
- 重试次数（retry_times）：默认0
- 重试间隔（retry_interval）：默认60秒
- 优先级（priority）：1-10，默认5
- 资源需求（resource_require）：JSON格式
- 任务描述（description）：可选

#### 前置条件
- 操作者是租户成员（MEMBER及以上）
- 项目必须存在且状态正常

#### 业务规则
1. **唯一性验证**
   - (project_id, task_code)必须唯一
   - 不同项目可以有相同的task_code

2. **任务类型验证**
   - SHELL：executor_config必须包含script字段
   - PYTHON：executor_config必须包含script或file字段，可选python_version
   - DOCKER：executor_config必须包含image、command字段
   - K8S_JOB：executor_config必须包含yaml_config或job_spec

3. **调度类型验证**
   - MANUAL：无需cron_expression
   - CRON：cron_expression必填且格式合法
   - DEPENDENCY：依赖其他任务触发，无需cron_expression

4. **Cron表达式验证**
   - 支持标准5位或6位Cron格式
   - 秒 分 时 日 月 周
   - 示例：`0 0 2 * * *`（每天凌晨2点）
   - 使用第三方库验证合法性

5. **资源需求格式**
   资源需求JSON示例：
   ```
   {
     "cpu": 2,              // CPU核数
     "memory": "4Gi",       // 内存大小
     "gpu": 1,              // GPU卡数（可选）
     "gpu_type": "V100",    // GPU型号（可选）
     "disk": "10Gi",        // 磁盘空间（可选）
     "node_selector": {     // 节点选择器（可选）
       "zone": "cn-beijing",
       "env": "production"
     }
   }
   ```

6. **默认值设置**
   - status默认为1（启用）
   - version默认为0（乐观锁版本）
   - creator_user_id记录创建者
   - created_at和updated_at自动填充

7. **配额检查**
   - 检查租户的max_tasks限制
   - 统计租户下所有项目的任务总数
   - 超出限制则拒绝创建

#### 执行器配置示例

**SHELL类型**：
```
{
  "script": "mysqldump -u root -p mydb > /backup/mydb.sql",
  "timeout": 1800,
  "env": {
    "MYSQL_PWD": "password"
  }
}
```

**PYTHON类型**：
```
{
  "script": "import sys; print('hello')",
  "python_version": "3.9",
  "requirements": ["pandas==1.5.0", "numpy==1.23.0"]
}
```

**DOCKER类型**：
```
{
  "image": "python:3.9-slim",
  "command": "python train.py --epochs 100",
  "volumes": ["/data:/data"],
  "env": {
    "MODEL_PATH": "/data/model"
  }
}
```

#### 异常场景
- task_code重复：返回"任务编码在该项目下已存在"
- 配额已满：返回"租户已达到最大任务数限制"
- executor_config格式错误：返回"执行器配置格式不正确"
- cron_expression不合法：返回"Cron表达式格式错误"
- 权限不足：返回"您没有权限创建任务"

### 4.3 任务更新流程

#### 可更新字段
- task_name - 任务名称
- task_type - 任务类型（慎重）
- executor_config - 执行器配置
- schedule_type - 调度类型
- cron_expression - Cron表达式
- timeout_seconds - 超时时间
- retry_times - 重试次数
- retry_interval - 重试间隔
- priority - 优先级
- resource_require - 资源需求
- alert_on_failure - 失败告警
- alert_on_timeout - 超时告警
- description - 任务描述
- status - 任务状态

#### 前置条件
- 操作者是任务创建者，或租户ADMIN/OWNER
- 任务必须存在且未删除

#### 业务规则
1. **不可更新字段**
   - task_code不允许修改
   - project_id不允许修改
   - creator_user_id不允许修改

2. **乐观锁控制**
   - 更新时必须提供当前version值
   - 更新成功后version自动加1
   - version不匹配时拒绝更新，提示并发冲突

3. **状态变更影响**
   - 禁用任务（status=0）后：
     - 停止CRON类型的定时调度
     - 不影响已创建的PENDING实例
     - 不允许手动触发新实例
   - 启用任务（status=1）后：
     - 恢复定时调度
     - 重新计算下次调度时间

4. **配置变更影响**
   - executor_config、resource_require变更只影响新实例
   - 已创建的实例使用创建时的配置快照
   - timeout、retry配置变更立即生效

5. **调度变更处理**
   - 修改cron_expression后立即重新计算下次调度时间
   - 从CRON改为MANUAL需确认是否取消待调度实例
   - 从MANUAL改为CRON需设置cron_expression

#### 版本冲突处理
当多个用户同时修改任务时：
- 第一个提交成功，version从5变为6
- 第二个提交失败，因为提供的version=5已过期
- 提示用户刷新页面获取最新版本后再修改

#### 异常场景
- 任务不存在：返回"任务不存在或已删除"
- 版本冲突：返回"任务已被其他用户修改，请刷新后重试"
- 权限不足：返回"您没有权限修改该任务"
- 配置格式错误：返回具体的验证错误信息

### 4.4 任务查询流程

#### 查询场景
1. **项目下所有任务** - 任务列表页
2. **任务详情** - 包含统计和历史执行记录
3. **任务搜索** - 按名称、编码、类型搜索

#### 业务规则
1. **权限过滤**
   - 用户只能查看所在租户的任务
   - 根据项目权限进一步过滤

2. **状态过滤**
   - 默认只显示deleted=0的任务
   - 可选显示已禁用任务（status=0）

3. **统计信息**
   - 任务总执行次数
   - 成功/失败次数和比率
   - 平均执行时长
   - 最近一次执行时间和状态
   - 下次调度时间（CRON类型）

4. **分页和排序**
   - 默认按created_at降序
   - 支持按名称、优先级、最近执行时间排序
   - 分页大小可配置，默认20条

#### 返回字段
- 任务基础信息
- 创建者信息
- 调度信息（类型、表达式、下次执行时间）
- 资源需求信息
- 统计信息

### 4.5 任务删除流程

#### 输入参数
- 任务ID（task_id）

#### 前置条件
- 操作者是任务创建者，或租户OWNER
- 需要二次确认

#### 业务规则
1. **级联检查**
   - 检查是否有运行中的任务实例
   - 检查是否被工作流引用
   - 检查是否有任务依赖关系

2. **删除策略**
   - **推荐**：软删除（deleted=1）
   - 保留历史执行记录
   - 同步删除task_dependency中的依赖关系

3. **清理操作**
   - 停止定时调度
   - 取消所有PENDING状态的实例
   - 不影响RUNNING状态的实例（让其执行完成）

4. **级联影响**
   - 如果任务被工作流引用，提示是否同时删除工作流
   - 如果有依赖关系，自动清理依赖记录

#### 数据一致性要求
- 整个删除过程必须在事务中
- 删除任务和依赖关系必须原子操作

#### 异常场景
- 有运行中实例：返回"该任务有正在运行的实例，请等待完成后删除"
- 被工作流引用：返回"该任务被工作流引用，请先解除引用"
- 权限不足：返回"只有任务创建者或租户所有者可以删除任务"

---

## 5. 任务依赖与DAG管理

### 5.1 业务概述
定义任务之间的依赖关系，构建有向无环图（DAG），实现复杂的工作流编排。

### 5.2 依赖关系创建流程

#### 输入参数
- 父任务ID（parent_task_id）：被依赖的任务
- 子任务ID（child_task_id）：依赖其他任务的任务
- 依赖类型（dependency_type）：SUCCESS/FAILED/FINISHED

#### 前置条件
- 父任务和子任务必须存在且未删除
- 父任务和子任务必须在同一项目下
- 操作者有权限修改这两个任务

#### 业务规则
1. **唯一性约束**
   - (parent_task_id, child_task_id)必须唯一
   - 不允许重复添加相同的依赖关系

2. **自环检测**
   - 不允许任务依赖自己（parent_task_id != child_task_id）
   - 添加前进行验证

3. **循环依赖检测**
   - 添加新依赖前，检测是否形成环路
   - 使用深度优先搜索（DFS）或拓扑排序算法
   - 如果形成环，拒绝添加

4. **依赖类型说明**
   - **SUCCESS**：父任务执行成功（status=SUCCESS）后，子任务才能执行
   - **FAILED**：父任务执行失败（status=FAILED）后，子任务才能执行（用于错误处理）
   - **FINISHED**：父任务执行完成（SUCCESS或FAILED）后，子任务都可以执行（用于清理任务）

5. **跨项目依赖限制**
   - 不支持跨项目的任务依赖
   - 确保父子任务的project_id相同

6. **依赖层级限制（可选）**
   - 限制最大依赖深度，如10层
   - 防止过于复杂的依赖链

#### 循环依赖检测算法（逻辑描述）

**步骤**：
1. 构建当前所有依赖关系的图（邻接表）
2. 将新依赖关系加入图中
3. 从任意节点开始进行拓扑排序
4. 如果拓扑排序失败（存在环），则拒绝添加
5. 如果拓扑排序成功，则允许添加

**优化**：
- 只检测涉及的子图，不需要检测整个图
- 从child_task开始DFS，如果能回到parent_task，则存在环

#### 异常场景
- 自环：返回"任务不能依赖自己"
- 循环依赖：返回"添加该依赖会形成循环，请检查依赖关系"
- 重复依赖：返回"该依赖关系已存在"
- 跨项目：返回"不支持跨项目的任务依赖"
- 任务不存在：返回"父任务或子任务不存在"
- 权限不足：返回"您没有权限修改任务依赖"

### 5.3 依赖关系查询流程

#### 查询场景
1. **查询任务的所有父任务** - 谁依赖我
2. **查询任务的所有子任务** - 我依赖谁
3. **查询完整的DAG图** - 用于可视化展示

#### 业务规则
1. **直接依赖查询**
   - 根据child_task_id查询所有parent_task_id
   - 根据parent_task_id查询所有child_task_id
   - 包含依赖类型信息

2. **递归依赖查询**
   - 查询任务的所有祖先节点（递归向上）
   - 查询任务的所有后代节点（递归向下）
   - 用于分析影响范围

3. **DAG可视化**
   - 返回节点列表（任务信息）
   - 返回边列表（依赖关系）
   - 前端使用图形库渲染（如ECharts、D3.js）

4. **拓扑排序**
   - 对DAG进行拓扑排序，返回执行顺序
   - 用于确定任务的调度优先级

#### 返回字段
- 依赖关系ID
- 父任务信息（id, name, code）
- 子任务信息（id, name, code）
- 依赖类型
- 创建时间

### 5.4 依赖关系删除流程

#### 输入参数
- 依赖关系ID（dependency_id）
- 或 (parent_task_id, child_task_id)

#### 前置条件
- 操作者有权限修改相关任务
- 依赖关系必须存在

#### 业务规则
1. **物理删除**
   - 直接从task_dependency表删除记录
   - 不使用软删除

2. **影响分析**
   - 删除依赖后，子任务可以独立调度
   - 删除依赖不影响已创建的任务实例

3. **批量删除**
   - 删除任务时，自动删除相关的所有依赖关系
   - 包括该任务作为父任务和子任务的依赖

#### 异常场景
- 依赖不存在：返回"依赖关系不存在"
- 权限不足：返回"您没有权限删除依赖关系"

### 5.5 DAG执行逻辑

#### 执行前依赖检查

**输入**：
- 待执行的任务实例ID

**步骤**：
1. 查询该任务的所有父任务（parent_task_id）
2. 对于每个父任务，查询在同一workflow_instance中的实例
3. 根据dependency_type判断依赖是否满足：
   - SUCCESS：父实例status必须为SUCCESS
   - FAILED：父实例status必须为FAILED
   - FINISHED：父实例status必须为SUCCESS或FAILED
4. 只有所有依赖都满足，任务实例才能进入READY状态

#### 状态流转

**PENDING → WAITING**：
- 任务实例创建后，如果有依赖关系，进入WAITING状态

**WAITING → READY**：
- 当所有父任务实例满足依赖条件后，进入READY状态

**READY → RUNNING**：
- 调度器选中该实例，分配资源后开始执行

#### 依赖失败处理

**场景1：父任务失败且依赖类型为SUCCESS**
- 子任务实例状态设置为SKIPPED（跳过）
- 记录失败原因：依赖未满足

**场景2：父任务成功但依赖类型为FAILED**
- 子任务实例状态设置为SKIPPED
- 记录原因：父任务未失败，无需执行

**场景3：父任务超时**
- 视为FAILED处理
- 根据依赖类型决定子任务是否执行

#### 并行执行优化

**识别可并行任务**：
- 拓扑排序后，同一层级的任务可以并行执行
- 示例：
  ```
  层级0: Task A
  层级1: Task B, Task C  ← 可并行
  层级2: Task D
  ```

**并发控制**：
- 限制同时执行的任务实例数（租户级别）
- 根据资源可用性动态调整并发度

---

## 6. 任务实例执行

### 6.1 业务概述
任务实例是任务定义的具体执行记录，记录每次执行的状态、时间、资源使用等信息。

### 6.2 任务实例创建流程

#### 触发方式
1. **手动触发**：用户在界面点击"立即执行"
2. **定时触发**：CRON类型任务到达调度时间
3. **依赖触发**：父任务完成后自动触发子任务
4. **API触发**：通过API接口提交任务

#### 输入参数
- 任务ID（task_id）
- 触发类型（trigger_type）：MANUAL/CRON/DEPENDENCY/API
- 触发用户ID（trigger_user_id）：手动或API触发时必填
- 实例参数（instance_params）：可选，运行时参数覆盖
- 预定调度时间（scheduled_time）：可选

#### 前置条件
- 任务必须存在且状态正常（status=1）
- 任务所属项目状态正常
- 租户状态正常且未过期

#### 业务规则
1. **实例编码生成**
   - 格式：`{task_code}_{yyyyMMddHHmmss}_{6位随机字符}`
   - 示例：`backup-db_20260403180000_a1b2c3`
   - 确保全局唯一性（数据库唯一约束）

2. **幂等性保证**
   - 相同instance_code重复提交时，返回已存在的实例
   - 不创建新实例，避免重复执行
   - 适用于API重试场景

3. **配额检查**
   - 检查租户的max_pending_tasks限制
   - 统计当前PENDING状态的实例数
   - 超出限制则拒绝创建

4. **初始状态设置**
   - status默认为PENDING
   - priority继承自任务定义
   - retry_count初始化为0
   - version初始化为0（乐观锁）

5. **参数处理**
   - 如果提供instance_params，合并到任务的executor_config
   - 实例参数优先级高于任务定义
   - 记录最终的执行配置快照

6. **工作流关联**
   - 如果是工作流触发，记录workflow_instance_id
   - 用于批量管理和统计

#### 实例参数覆盖示例

**任务定义的executor_config**：
```
{
  "script": "python train.py --epochs {epochs} --batch {batch}",
  "epochs": 100,
  "batch": 32
}
```

**实例参数instance_params**：
```
{
  "epochs": 200,
  "lr": 0.001
}
```

**最终执行配置**：
```
{
  "script": "python train.py --epochs 200 --batch 32 --lr 0.001",
  "epochs": 200,
  "batch": 32,
  "lr": 0.001
}
```

#### 异常场景
- 任务不存在：返回"任务不存在或已删除"
- 任务被禁用：返回"任务已被禁用，无法执行"
- 配额已满：返回"待执行任务数已达上限，请稍后重试"
- 实例编码冲突：返回已存在的实例（幂等）

### 6.3 任务实例状态流转

#### 状态定义

| 状态 | 说明 | 可转换到 |
|------|------|---------|
| PENDING | 已提交，等待调度 | WAITING, READY, CANCELLED |
| WAITING | 等待依赖满足 | READY, SKIPPED, CANCELLED |
| READY | 依赖已满足，等待资源分配 | RUNNING, CANCELLED |
| RUNNING | 正在执行 | SUCCESS, FAILED, TIMEOUT, CANCELLED |
| SUCCESS | 执行成功 | 无（终态） |
| FAILED | 执行失败 | PENDING（重试） |
| TIMEOUT | 执行超时 | PENDING（重试） |
| CANCELLED | 已取消 | 无（终态） |
| SKIPPED | 已跳过（依赖未满足） | 无（终态） |

#### 状态流转规则

**PENDING → WAITING**
- 触发条件：创建实例后，检测到有未满足的依赖
- 操作：无需分配资源，等待依赖

**WAITING → READY**
- 触发条件：所有依赖任务都满足条件
- 操作：进入可调度队列

**WAITING → SKIPPED**
- 触发条件：父任务失败且依赖类型为SUCCESS
- 操作：标记为跳过，记录原因

**PENDING/READY → RUNNING**
- 触发条件：调度器选中，资源分配成功
- 操作：
  - 更新resource_node_id
  - 记录start_time
  - 扣减资源配额
  - 发送执行命令到执行器

**RUNNING → SUCCESS**
- 触发条件：任务正常结束，退出码为0
- 操作：
  - 记录end_time和duration_ms
  - 释放资源配额
  - 触发依赖的子任务

**RUNNING → FAILED**
- 触发条件：任务异常结束，退出码非0
- 操作：
  - 记录end_time、duration_ms、error_message
  - 释放资源配额
  - 检查是否需要重试

**RUNNING → TIMEOUT**
- 触发条件：执行时间超过timeout_seconds
- 操作：
  - 强制终止任务进程
  - 记录error_message："执行超时"
  - 释放资源配额
  - 检查是否需要重试

**FAILED/TIMEOUT → PENDING**
- 触发条件：retry_count < retry_times
- 操作：
  - retry_count加1
  - 重置为PENDING状态
  - 等待retry_interval秒后重新调度

**任意状态 → CANCELLED**
- 触发条件：用户手动取消
- 操作：
  - 如果正在运行，发送终止信号
  - 释放已分配的资源
  - 记录取消原因和操作者

#### 状态更新并发控制

使用乐观锁（version字段）：
- 更新状态时，WHERE条件包含当前version
- 更新成功后，version自动加1
- 如果version不匹配，重新查询后重试

### 6.4 任务重试逻辑

#### 重试判断

**是否需要重试**：
- 当前状态为FAILED或TIMEOUT
- retry_count < task.retry_times
- 任务未被删除且状态正常

**重试间隔**：
- 固定间隔：使用task.retry_interval（秒）
- 指数退避（可选）：第n次重试间隔 = base_interval * 2^(n-1)
  - 第1次：60秒
  - 第2次：120秒
  - 第3次：240秒

#### 重试流程

**步骤**：
1. 检查重试条件是否满足
2. retry_count加1
3. 状态重置为PENDING
4. 清空resource_node_id
5. 清空start_time、end_time、duration_ms
6. 记录重试日志："第{n}次重试"
7. 如果配置了重试间隔，延迟scheduled_time

#### 重试失败处理

**达到最大重试次数**：
- 状态保持为FAILED
- error_message追加："已达到最大重试次数"
- 触发失败告警（如果配置了alert_on_failure）
- 不再自动重试，可手动重跑

**重试依然失败**：
- 每次重试失败都记录错误信息
- 保留所有重试的执行日志
- 用于问题排查

### 6.5 任务取消流程

#### 输入参数
- 任务实例ID（instance_id）
- 取消原因（cancel_reason）：可选

#### 前置条件
- 操作者有权限取消任务（创建者或租户ADMIN）
- 实例状态不是终态（SUCCESS/FAILED/TIMEOUT/CANCELLED）

#### 业务规则
1. **状态判断**
   - PENDING/WAITING/READY：直接标记为CANCELLED
   - RUNNING：发送终止信号，等待确认

2. **资源释放**
   - 如果已分配资源，立即释放
   - 更新resource_node的可用资源
   - 更新resource_quota的使用量

3. **进程终止**
   - RUNNING状态：发送SIGTERM信号
   - 等待10秒，如果未退出，发送SIGKILL强制终止
   - 记录终止日志

4. **依赖处理**
   - 子任务依赖该任务时，视为SKIPPED
   - 不触发后续任务

5. **记录信息**
   - 记录取消时间、取消用户、取消原因
   - 状态设置为CANCELLED
   - 在execution_log记录取消操作

#### 异常场景
- 实例不存在：返回"实例不存在"
- 已是终态：返回"任务已完成，无法取消"
- 权限不足：返回"您没有权限取消该任务"
- 终止失败：返回"任务终止失败，请联系管理员"

### 6.6 任务实例查询流程

#### 查询场景
1. **任务历史执行记录** - 某个任务的所有实例
2. **实例详情** - 包含日志和资源使用情况
3. **全局实例列表** - 租户下所有实例，支持多维度过滤

#### 查询条件
- 按任务ID过滤
- 按状态过滤（支持多选）
- 按触发类型过滤
- 按时间范围过滤（created_at, start_time）
- 按触发用户过滤
- 按工作流实例过滤

#### 业务规则
1. **权限过滤**
   - 用户只能查询所在租户的实例
   - 根据项目权限进一步过滤

2. **分页和排序**
   - 默认按created_at降序
   - 支持按start_time、duration_ms排序
   - 分页大小可配置，默认50条

3. **统计信息**
   - 总执行次数
   - 各状态的实例数量
   - 平均执行时长
   - 成功率

#### 返回字段
- 实例基础信息
- 关联任务信息
- 触发信息
- 执行时间信息
- 资源分配信息
- 状态和错误信息

---

## 7. 工作流管理

### 7.1 业务概述
工作流是一组任务的有序组合，通过DAG定义任务之间的依赖关系，支持定时调度和批量执行。

### 7.2 工作流创建流程

#### 输入参数
- 项目ID（project_id）
- 工作流名称（workflow_name）：1-100字符
- 工作流编码（workflow_code）：3-50字符
- 工作流描述（description）：可选
- DAG定义（dag_json）：JSON格式
- 调度类型（schedule_type）：MANUAL/CRON
- Cron表达式（cron_expression）：CRON类型必填
- 超时时间（timeout_seconds）：默认7200秒
- 失败告警（alert_on_failure）：0/1

#### 前置条件
- 操作者是租户成员（MEMBER及以上）
- 项目必须存在且状态正常
- DAG中的所有任务必须在同一项目下

#### 业务规则
1. **唯一性验证**
   - (project_id, workflow_code)必须唯一

2. **DAG格式验证**
   - dag_json必须是合法的JSON
   - 必须包含tasks数组和dependencies数组
   - 每个task必须指定task_id或task_code
   - 每个dependency必须指定parent和child

3. **DAG合法性检查**
   - 所有引用的任务必须存在
   - 不允许有孤立节点（没有依赖关系的节点）
   - 不允许有环路（循环依赖）
   - 至少有一个根节点（没有父节点的任务）

4. **拓扑排序验证**
   - 对DAG进行拓扑排序
   - 排序失败说明有环路，拒绝创建

5. **调度配置**
   - CRON类型：验证cron_expression合法性
   - 计算next_schedule_time（下次调度时间）
   - MANUAL类型：next_schedule_time为NULL

6. **默认值设置**
   - status默认为1（启用）
   - version默认为0（乐观锁）
   - creator_user_id记录创建者

#### DAG JSON格式示例

```
{
  "tasks": [
    {
      "task_id": 1,
      "task_code": "extract"
    },
    {
      "task_id": 2,
      "task_code": "transform"
    },
    {
      "task_id": 3,
      "task_code": "load"
    },
    {
      "task_id": 4,
      "task_code": "notify"
    }
  ],
  "dependencies": [
    {
      "parent": "extract",
      "child": "transform",
      "type": "SUCCESS"
    },
    {
      "parent": "transform",
      "child": "load",
      "type": "SUCCESS"
    },
    {
      "parent": "load",
      "child": "notify",
      "type": "FINISHED"
    }
  ]
}
```

**执行顺序**：
1. extract（根节点，第一个执行）
2. transform（extract成功后执行）
3. load（transform成功后执行）
4. notify（load完成后执行，无论成功失败）

#### 异常场景
- workflow_code重复：返回"工作流编码在该项目下已存在"
- DAG格式错误：返回具体的JSON解析错误
- DAG有环路：返回"工作流定义包含循环依赖，请检查"
- 任务不存在：返回"DAG中引用的任务{task_code}不存在"
- cron表达式错误：返回"Cron表达式格式不正确"

### 7.3 工作流更新流程

#### 可更新字段
- workflow_name - 工作流名称
- description - 工作流描述
- dag_json - DAG定义（慎重）
- schedule_type - 调度类型
- cron_expression - Cron表达式
- timeout_seconds - 超时时间
- alert_on_failure - 失败告警
- status - 工作流状态

#### 前置条件
- 操作者是工作流创建者，或租户ADMIN/OWNER
- 工作流必须存在且未删除

#### 业务规则
1. **不可更新字段**
   - workflow_code不允许修改
   - project_id不允许修改
   - creator_user_id不允许修改

2. **乐观锁控制**
   - 更新时必须提供当前version值
   - 更新成功后version自动加1

3. **DAG变更影响**
   - 修改dag_json后，只影响新创建的实例
   - 已创建的实例使用创建时的DAG快照

4. **调度变更影响**
   - 修改cron_expression后，重新计算next_schedule_time
   - 从CRON改为MANUAL，清空next_schedule_time
   - 从MANUAL改为CRON，设置next_schedule_time

5. **状态变更影响**
   - 禁用工作流（status=0）后，停止定时调度
   - 启用工作流（status=1）后，恢复定时调度

#### 异常场景
- 工作流不存在：返回"工作流不存在或已删除"
- 版本冲突：返回"工作流已被其他用户修改，请刷新后重试"
- DAG验证失败：返回具体的验证错误
- 权限不足：返回"您没有权限修改该工作流"

### 7.4 工作流实例创建流程

#### 触发方式
1. **手动触发**：用户在界面点击"立即执行"
2. **定时触发**：CRON类型工作流到达调度时间
3. **API触发**：通过API接口提交工作流

#### 输入参数
- 工作流ID（workflow_id）
- 触发类型（trigger_type）：MANUAL/CRON/API
- 触发用户ID（trigger_user_id）：手动或API触发时必填

#### 前置条件
- 工作流必须存在且状态正常（status=1）
- 工作流所属项目状态正常
- 租户状态正常且未过期

#### 业务规则
1. **实例编码生成**
   - 格式：`{workflow_code}_{yyyyMMdd}_{序号}`
   - 示例：`daily-etl_20260403_001`
   - 同一天的实例序号递增

2. **幂等性保证**
   - 相同instance_code重复提交时，返回已存在的实例

3. **初始状态**
   - status默认为RUNNING
   - start_time记录当前时间
   - total_tasks、success_tasks、failed_tasks初始化为0

4. **批量创建任务实例**
   - 根据dag_json解析所有任务
   - 为每个任务创建一个task_instance
   - 所有任务实例的workflow_instance_id设置为当前实例
   - 根据依赖关系设置初始状态：
     - 根节点：READY
     - 非根节点：WAITING
   - total_tasks设置为任务数量

5. **更新下次调度时间**
   - 如果是CRON触发，计算并更新workflow.next_schedule_time
   - 使用Cron表达式计算下次执行时间

#### 异常场景
- 工作流不存在：返回"工作流不存在或已删除"
- 工作流被禁用：返回"工作流已被禁用，无法执行"
- 实例编码冲突：返回已存在的实例（幂等）

### 7.5 工作流实例状态流转

#### 状态定义

| 状态 | 说明 | 转换条件 |
|------|------|---------|
| RUNNING | 正在执行 | 所有任务未完成 |
| SUCCESS | 执行成功 | 所有任务都成功 |
| FAILED | 执行失败 | 至少一个任务失败 |
| CANCELLED | 已取消 | 用户手动取消 |

#### 状态更新时机

**创建时**：
- 状态设置为RUNNING
- 记录start_time

**任务完成时**：
- 每当一个任务实例完成，更新success_tasks或failed_tasks
- 检查是否所有任务都完成
- 如果都完成，根据结果设置工作流实例状态

**状态判断逻辑**：
- 所有任务都SUCCESS → 工作流SUCCESS
- 至少一个任务FAILED → 工作流FAILED
- 至少一个任务TIMEOUT → 工作流FAILED
- 所有任务都SKIPPED → 工作流FAILED
- 混合状态（部分SUCCESS，部分SKIPPED） → 工作流SUCCESS

**完成时**：
- 记录end_time
- 计算duration_ms = end_time - start_time
- 触发失败告警（如果配置了alert_on_failure且状态为FAILED）

#### 超时处理

**工作流级别超时**：
- 如果end_time - start_time > timeout_seconds
- 取消所有未完成的任务实例
- 工作流状态设置为FAILED
- error_message记录："工作流执行超时"

### 7.6 工作流实例取消流程

#### 输入参数
- 工作流实例ID（workflow_instance_id）
- 取消原因（cancel_reason）：可选

#### 前置条件
- 操作者有权限取消工作流
- 实例状态为RUNNING

#### 业务规则
1. **批量取消任务**
   - 查询该工作流实例下的所有任务实例
   - 对每个非终态的任务实例执行取消操作
   - PENDING/WAITING/READY：直接标记为CANCELLED
   - RUNNING：发送终止信号

2. **状态更新**
   - 工作流实例状态设置为CANCELLED
   - 记录end_time
   - 计算duration_ms

3. **资源释放**
   - 释放所有已分配的资源
   - 更新resource_quota

#### 异常场景
- 实例不存在：返回"工作流实例不存在"
- 已完成：返回"工作流已完成，无法取消"
- 权限不足：返回"您没有权限取消该工作流"

### 7.7 工作流实例查询流程

#### 查询场景
1. **工作流历史执行记录** - 某个工作流的所有实例
2. **实例详情** - 包含所有任务实例的状态
3. **全局实例列表** - 租户下所有工作流实例

#### 查询条件
- 按工作流ID过滤
- 按状态过滤
- 按触发类型过滤
- 按时间范围过滤

#### 返回字段
- 工作流实例基础信息
- 关联工作流信息
- 触发信息
- 执行统计（total_tasks, success_tasks, failed_tasks）
- 执行时间信息
- 所有任务实例列表（可选）

---

## 8. 调度器核心逻辑

### 8.1 业务概述
调度器是系统的核心组件，负责扫描待执行任务、依赖检查、资源分配、任务调度和状态监控。

### 8.2 调度器主循环

#### 工作模式
- **单机模式**：单个调度器实例
- **集群模式**：多个调度器实例，通过Leader选举保证只有一个活跃

#### 主循环步骤

**步骤1：Leader选举（集群模式）**
- 使用Redis分布式锁实现Leader选举
- 锁key：`scheduler:leader:lock`
- 锁超时时间：30秒
- Watch Dog自动续期机制
- 非Leader节点等待

**步骤2：扫描待调度任务实例**
- 查询条件：
  - status = 'PENDING' 或 'READY'
  - scheduled_time <= 当前时间（如果设置）
  - task.status = 1（任务未禁用）
  - project.status = 1（项目未禁用）
  - tenant.status = 1（租户未禁用）
- 排序规则：
  - 按priority降序（优先级高的先调度）
  - 相同优先级按created_at升序（先提交的先调度）
- 限制数量：每次最多取100条

**步骤3：依赖检查**
- 对每个任务实例，检查是否有依赖
- 如果有依赖且状态为PENDING，更新为WAITING
- 如果所有依赖已满足，更新为READY
- 如果依赖未满足，跳过本次调度

**步骤4：资源分配**
- 对每个READY状态的任务实例，尝试分配资源
- 根据resource_require查找可用节点
- 使用乐观锁更新节点资源和租户配额
- 分配成功后记录resource_node_id

**步骤5：提交执行**
- 将分配好资源的任务实例提交到执行器
- 更新状态为RUNNING
- 记录start_time
- 触发执行器开始执行

**步骤6：超时检测**
- 查询所有RUNNING状态的任务实例
- 如果(当前时间 - start_time) > timeout_seconds
- 发送终止信号，更新状态为TIMEOUT
- 检查是否需要重试

**步骤7：心跳检测**
- 检查所有RUNNING状态的任务实例对应的节点
- 如果节点心跳超时（last_heartbeat_time超过5分钟）
- 标记节点为OFFLINE
- 任务实例状态设置为FAILED，原因："节点失联"
- 根据重试策略决定是否重调度

**步骤8：工作流调度**
- 扫描CRON类型的工作流
- next_schedule_time <= 当前时间
- 创建工作流实例
- 批量创建任务实例

**步骤9：休眠**
- 休眠5秒（可配置）
- 继续下一轮循环

### 8.3 依赖检查逻辑

#### 输入
- 任务实例ID

#### 输出
- 依赖是否满足：true/false
- 如果不满足，更新状态为WAITING

#### 检查步骤

**步骤1：查询依赖关系**
- 根据task_id查询task_dependency表
- 获取所有parent_task_id和dependency_type

**步骤2：查询父任务实例**
- 对于每个parent_task_id，查询在同一workflow_instance下的实例
- 如果没有workflow_instance_id（独立任务）：
  - 查询该任务最近一次执行的实例
  - 或者查询最近成功的实例（根据业务需求）

**步骤3：判断依赖条件**
- 对于每个父任务实例，根据dependency_type判断：
  - SUCCESS：父实例status必须为'SUCCESS'
  - FAILED：父实例status必须为'FAILED'
  - FINISHED：父实例status必须为'SUCCESS'或'FAILED'
- 如果父实例状态为PENDING/WAITING/READY/RUNNING，依赖未满足

**步骤4：返回结果**
- 所有父任务依赖都满足 → 返回true
- 任一父任务依赖未满足 → 返回false

#### 特殊场景处理

**父任务不存在**：
- 如果找不到父任务实例，视为依赖未满足
- 记录警告日志

**父任务跳过**：
- 如果父任务状态为SKIPPED，视为依赖未满足
- 子任务也标记为SKIPPED

**父任务取消**：
- 如果父任务状态为CANCELLED，子任务标记为SKIPPED

### 8.4 资源调度策略

#### 调度目标
- 最大化资源利用率
- 保证多租户公平性
- 优先级高的任务优先调度
- 避免资源碎片化

#### 调度算法

**算法1：最佳匹配（Best Fit）**
- 遍历所有ONLINE状态的节点
- 过滤出资源充足的节点
- 选择剩余资源最接近需求的节点
- 优点：减少资源浪费，避免碎片化
- 缺点：计算复杂度高

**算法2：首次匹配（First Fit）**
- 遍历节点，找到第一个资源充足的节点
- 立即分配，停止搜索
- 优点：计算速度快
- 缺点：可能导致资源碎片化

**算法3：优先级队列（Priority Queue）**
- 根据任务优先级排序
- 优先级高的任务优先分配资源
- 相同优先级按FIFO顺序

**推荐算法：加权评分**
- 综合考虑节点负载、剩余资源、网络距离等因素
- 评分公式：
  ```
  score = w1 * cpu_available / cpu_total
        + w2 * memory_available / memory_total
        + w3 * gpu_available / gpu_total
        - w4 * running_tasks / max_tasks
  ```
- 权重可配置

#### 节点选择步骤

**步骤1：过滤节点**
- 节点状态为ONLINE
- 剩余资源充足：
  - available_cpu >= required_cpu
  - available_memory_mb >= required_memory_mb
  - available_gpu >= required_gpu

**步骤2：标签匹配（可选）**
- 如果任务指定了node_selector
- 匹配节点的labels字段
- 只选择标签匹配的节点

**步骤3：计算评分**
- 对过滤后的节点计算评分
- 按评分降序排序

**步骤4：尝试分配**
- 选择评分最高的节点
- 使用乐观锁更新节点资源
- 如果更新失败（版本冲突），选择下一个节点
- 最多重试3次

**步骤5：配额扣减**
- 更新租户的resource_quota
- 使用乐观锁更新used_cpu、used_memory_mb、used_gpu
- 如果更新失败，回滚节点资源分配

#### 资源不足处理

**无可用节点**：
- 任务实例保持READY状态
- 等待下次调度
- 记录日志："资源不足，等待调度"

**配额不足**：
- 检查是否超过租户配额
- 如果超过，拒绝分配
- 记录日志："租户配额不足"
- 任务实例状态不变，继续等待

**资源碎片化**：
- 定期进行资源整理（可选）
- 迁移小任务，释放大块连续资源

### 8.5 任务提交执行

#### 执行器类型
- **本地执行器**：在调度器所在服务器执行
- **远程执行器**：在资源节点上的Agent执行
- **容器执行器**：Docker或Kubernetes执行

#### 提交流程

**步骤1：构建执行命令**
- 根据task_type和executor_config生成执行命令
- SHELL：直接执行脚本
- PYTHON：生成Python脚本文件，使用python命令执行
- DOCKER：生成docker run命令
- K8S_JOB：生成Kubernetes Job YAML，使用kubectl创建

**步骤2：发送执行请求**
- 调用资源节点的Agent API
- 请求参数：
  - task_instance_id
  - command（执行命令）
  - timeout_seconds
  - env（环境变量）
- 使用HTTP或gRPC协议

**步骤3：更新状态**
- 如果Agent返回成功，更新状态为RUNNING
- 如果Agent返回失败，更新状态为FAILED
- 记录start_time

**步骤4：启动监控**
- 注册到心跳监控列表
- 定期检查任务进程是否存活
- 收集执行日志

#### 执行失败处理

**Agent不可达**：
- 标记节点为OFFLINE
- 任务实例状态设置为FAILED
- 根据重试策略重新调度

**执行命令错误**：
- 记录错误信息到error_message
- 状态设置为FAILED
- 根据重试策略决定是否重试

### 8.6 任务监控与回调

#### 心跳机制

**Agent心跳**：
- Agent每30秒向调度器发送心跳
- 更新resource_node的last_heartbeat_time
- 报告节点当前状态和资源使用情况

**任务心跳**：
- 对于长时间运行的任务，Agent定期报告执行进度
- 更新task_instance的心跳时间（可选字段）

**心跳超时处理**：
- 如果节点心跳超时（超过5分钟）
- 标记节点为OFFLINE
- 所有在该节点运行的任务标记为FAILED
- 根据重试策略重新调度

#### 回调接口

**任务完成回调**：
- Agent在任务执行完成后，调用调度器的回调接口
- 请求参数：
  - task_instance_id
  - status（SUCCESS/FAILED/TIMEOUT）
  - exit_code
  - error_message（如果失败）
  - duration_ms

**回调处理**：
- 更新任务实例状态
- 记录end_time和duration_ms
- 释放资源
- 触发依赖的子任务
- 更新工作流实例状态（如果有）
- 触发告警（如果配置）

#### 日志收集

**实时日志流**：
- Agent将任务的stdout和stderr实时推送到调度器
- 使用WebSocket或gRPC流
- 调度器解析日志，写入execution_log表

**日志缓存**：
- 大量日志先缓存在Redis
- 批量写入数据库，提高性能

**日志存储**：
- 小日志（<10MB）存储在数据库
- 大日志存储在对象存储（MinIO/OSS）
- 数据库只保存日志链接

---

## 9. 资源管理与调度

### 9.1 业务概述
管理集群资源节点，监控资源使用情况，实现资源配额管理和公平调度。

### 9.2 资源节点注册

#### 输入参数
- 节点名称（node_name）
- 节点主机（node_host）
- 节点端口（node_port）
- 节点类型（node_type）：CPU/GPU/MIXED
- 总CPU核数（total_cpu）
- 总内存（total_memory_mb）
- 总GPU卡数（total_gpu）：可选
- GPU型号（gpu_model）：可选
- 节点标签（labels）：JSON格式

#### 业务规则
1. **唯一性验证**
   - (node_host, node_port)必须唯一
   - 同一主机可以有多个节点（不同端口）

2. **自动初始化**
   - available_cpu = total_cpu
   - available_memory_mb = total_memory_mb
   - available_gpu = total_gpu
   - status = 'ONLINE'
   - last_heartbeat_time = 当前时间

3. **节点类型**
   - CPU：只提供CPU和内存资源
   - GPU：提供CPU、内存和GPU资源
   - MIXED：可以同时运行CPU和GPU任务

4. **标签示例**：
   ```
   {
     "zone": "cn-beijing-a",
     "env": "production",
     "disk_type": "ssd",
     "network": "10g"
   }
   ```

#### 异常场景
- 节点已存在：返回"节点已注册"
- 参数不合法：返回具体的验证错误

### 9.3 资源节点心跳

#### 输入参数
- 节点ID（node_id）
- 当前CPU使用量（current_cpu_used）
- 当前内存使用量（current_memory_used）
- 当前GPU使用量（current_gpu_used）

#### 业务规则
1. **更新心跳时间**
   - last_heartbeat_time = 当前时间

2. **更新可用资源**
   - available_cpu = total_cpu - current_cpu_used
   - available_memory_mb = total_memory_mb - current_memory_used
   - available_gpu = total_gpu - current_gpu_used

3. **状态检查**
   - 如果节点状态为OFFLINE，自动恢复为ONLINE
   - 记录恢复日志

4. **异常检测**
   - 如果可用资源为负数，记录警告日志
   - 可能是统计误差，自动修正为0

#### 心跳频率
- 正常情况：每30秒一次
- 节点繁忙：每60秒一次
- 超时阈值：5分钟无心跳则标记为OFFLINE

### 9.4 资源节点下线

#### 输入参数
- 节点ID（node_id）
- 下线原因（offline_reason）：可选

#### 前置条件
- 操作者是平台管理员

#### 业务规则
1. **状态更新**
   - status设置为'OFFLINE'或'MAINTENANCE'
   - 记录下线时间和原因

2. **任务处理**
   - 查询在该节点运行的所有任务实例
   - 对每个RUNNING状态的任务：
     - 发送终止信号
     - 状态设置为FAILED
     - 错误信息："节点下线"
     - 根据重试策略重新调度

3. **资源释放**
   - 释放该节点占用的所有配额
   - 更新租户的resource_quota

4. **下线模式**
   - **立即下线**：立即终止所有任务
   - **优雅下线**：等待当前任务执行完成，不再接收新任务

#### 异常场景
- 节点不存在：返回"节点不存在"
- 有正在运行的任务：提示确认是否强制下线

### 9.5 资源配额管理

#### 配额初始化

**触发时机**：
- 创建租户时自动创建配额记录

**默认配额**：
- max_cpu = 10核
- max_memory_mb = 10240（10GB）
- max_gpu = 0
- max_running_tasks = 50
- max_pending_tasks = 500

#### 配额更新

**输入参数**：
- 租户ID（tenant_id）
- 最大CPU核数（max_cpu）
- 最大内存（max_memory_mb）
- 最大GPU卡数（max_gpu）
- 最大运行任务数（max_running_tasks）
- 最大排队任务数（max_pending_tasks）

**前置条件**：
- 操作者是平台管理员

**业务规则**：
1. **配额下调检查**
   - 如果新配额小于当前使用量，提示警告
   - 可选：拒绝下调，或者允许下调但已用资源不回收

2. **乐观锁控制**
   - 使用version字段防止并发冲突

#### 配额分配（任务提交时）

**步骤1：检查配额**
- 查询租户的resource_quota
- 检查是否有足够的配额：
  - used_cpu + required_cpu <= max_cpu
  - used_memory_mb + required_memory_mb <= max_memory_mb
  - used_gpu + required_gpu <= max_gpu
  - running_tasks < max_running_tasks

**步骤2：预占配额**
- 使用乐观锁更新配额：
  - used_cpu += required_cpu
  - used_memory_mb += required_memory_mb
  - used_gpu += required_gpu
  - running_tasks += 1
  - version += 1
- WHERE条件包含version和配额充足的判断

**步骤3：配额不足处理**
- 如果更新影响行数为0，说明配额不足或并发冲突
- 重新查询配额，判断具体原因
- 返回错误信息："CPU配额不足"或"并发冲突，请重试"

#### 配额释放（任务完成时）

**步骤1：查询任务占用的资源**
- 从task_instance查询实际占用的资源

**步骤2：释放配额**
- 使用乐观锁更新配额：
  - used_cpu -= occupied_cpu
  - used_memory_mb -= occupied_memory_mb
  - used_gpu -= occupied_gpu
  - running_tasks -= 1
  - version += 1

**步骤3：容错处理**
- 如果释放时发现使用量已为0，记录警告日志
- 自动修正为0，防止负数

### 9.6 资源统计与监控

#### 实时统计

**节点级别统计**：
- 总资源、已用资源、可用资源
- 当前运行的任务数
- 节点健康状态
- 平均负载

**租户级别统计**：
- 配额使用率（CPU/Memory/GPU）
- 当前运行任务数
- 排队任务数
- 历史资源使用趋势

**全局统计**：
- 集群总资源和使用率
- 各租户的资源使用排名
- 资源热点和瓶颈分析

#### 性能快照

**采集频率**：
- 每5分钟采集一次

**采集指标**：
- 节点CPU使用率
- 节点内存使用率
- 节点GPU使用率
- 任务执行时长
- 任务排队时间

**数据存储**：
- 写入metric_snapshot表
- 定期归档历史数据（如1个月前的数据）

#### 资源预警

**预警规则**：
- CPU使用率超过80%：黄色预警
- CPU使用率超过90%：红色预警
- 内存使用率超过85%：黄色预警
- GPU使用率超过90%：红色预警
- 排队任务数超过100：提示扩容

**预警通知**：
- 发送邮件或钉钉消息给管理员
- 在监控面板显示预警标识

---

## 10. 执行日志管理

### 10.1 业务概述
收集、存储和查询任务执行过程中产生的日志，用于问题排查和审计。

### 10.2 日志收集

#### 日志来源
1. **stdout**：任务标准输出
2. **stderr**：任务标准错误输出
3. **system**：系统日志（如调度器日志）

#### 收集方式

**实时流式收集**：
- Agent通过WebSocket或gRPC流推送日志
- 调度器实时接收并处理
- 适用于需要实时监控的场景

**批量收集**：
- Agent将日志缓存到本地文件
- 任务完成后批量上传
- 适用于离线任务

#### 日志处理流程

**步骤1：接收日志**
- 调度器接收Agent发送的日志流
- 包含：task_instance_id、log_content、timestamp

**步骤2：日志解析**
- 识别日志级别（INFO/WARN/ERROR/DEBUG）
- 从日志内容中提取级别标识，如：
  - `[INFO]`、`INFO:`、`I:`
  - `[WARN]`、`WARNING:`、`W:`
  - `[ERROR]`、`ERROR:`、`E:`
- 无法识别的默认为INFO

**步骤3：日志缓存**
- 先写入Redis缓存（List或Stream）
- key：`task_instance:{instance_id}:logs`
- 缓存时间：24小时

**步骤4：日志持久化**
- 定时任务（每5秒）批量写入数据库
- 每次最多写入100条
- 减少数据库压力

**步骤5：大日志处理**
- 如果日志总大小超过10MB
- 写入对象存储（MinIO/OSS）
- 数据库只保存链接：
  ```
  log_content = "LOG_FILE_URL: https://oss.example.com/logs/instance_12345.log"
  ```

### 10.3 日志查询

#### 查询场景
1. **实时日志流** - 任务执行过程中实时查看
2. **历史日志** - 任务完成后查看完整日志
3. **日志搜索** - 根据关键字搜索日志

#### 查询参数
- 任务实例ID（task_instance_id）：必填
- 日志级别（log_level）：可选，支持多选
- 时间范围（start_time, end_time）：可选
- 关键字（keyword）：可选
- 分页参数（page, page_size）

#### 业务规则

**1. 实时日志查询**
- 从Redis缓存读取最新日志
- 使用WebSocket推送到前端
- 前端实时渲染
- 适用于RUNNING状态的任务

**2. 历史日志查询**
- 从数据库查询execution_log表
- 按log_time升序排列
- 支持分页
- 适用于已完成的任务

**3. 大日志查询**
- 如果log_content包含"LOG_FILE_URL"
- 返回对象存储的下载链接
- 前端下载文件或在线查看

**4. 日志搜索**
- 使用MySQL全文索引或ElasticSearch
- 根据keyword模糊匹配log_content
- 返回匹配的日志行及上下文（前后3行）

**5. 日志级别过滤**
- 根据log_level参数过滤
- 支持多选：`log_level IN ('WARN', 'ERROR')`

#### 返回字段
- 日志ID
- 日志级别
- 日志内容
- 日志时间
- 日志来源（stdout/stderr/system）

### 10.4 日志清理

#### 清理策略

**1. 数据库日志清理**
- 保留时间：默认30天
- 超过30天的日志移至归档表或删除
- 使用定时任务（每天凌晨2点执行）

**2. 对象存储日志清理**
- 保留时间：默认90天
- 超过90天的日志文件删除
- 使用对象存储的生命周期策略

**3. Redis缓存清理**
- 自动过期：TTL=24小时
- 任务完成后立即清理（可选）

#### 清理流程

**步骤1：查询过期日志**
- 查询created_at早于保留期限的日志

**步骤2：归档（可选）**
- 导出为文件，上传到归档存储
- 或者迁移到归档表

**步骤3：删除**
- 批量删除过期日志
- 每批1000条，避免锁表

**步骤4：更新元数据**
- 如果删除了对象存储文件
- 更新execution_log的log_content为"已归档"

### 10.5 日志分析

#### 错误日志提取

**目的**：
- 快速定位任务失败原因
- 在任务详情页展示错误摘要

**提取规则**：
- 查询该任务实例的所有ERROR级别日志
- 取最后5条错误日志
- 或者提取包含异常堆栈的日志

**展示格式**：
- 错误时间
- 错误内容（限制500字符）
- 完整堆栈（可展开）

#### 日志统计

**统计维度**：
- 各级别日志数量（INFO/WARN/ERROR）
- 最常见的错误信息（TOP 10）
- 错误发生时间分布

**使用场景**：
- 任务质量评估
- 问题模式识别
- 系统稳定性分析

---

## 11. 监控与告警

### 11.1 业务概述
监控系统运行状态，收集性能指标，配置告警规则，及时发现和通知异常。

### 11.2 告警规则配置

#### 输入参数
- 租户ID（tenant_id）
- 规则名称（rule_name）
- 规则类型（rule_type）：TASK_FAILURE/TASK_TIMEOUT/RESOURCE_SHORTAGE
- 目标类型（target_type）：PROJECT/TASK/WORKFLOW
- 目标ID（target_id）：可选，NULL表示全局
- 条件配置（condition_config）：JSON格式
- 通知渠道（notification_channels）：JSON格式
- 通知用户（notification_users）：JSON格式

#### 前置条件
- 操作者是租户的OWNER或ADMIN

#### 业务规则

**1. 规则类型说明**
- **TASK_FAILURE**：任务失败时触发
- **TASK_TIMEOUT**：任务超时时触发
- **RESOURCE_SHORTAGE**：资源不足时触发
- **WORKFLOW_FAILURE**：工作流失败时触发（可扩展）

**2. 条件配置示例**

**任务失败率告警**：
```
{
  "metric": "failure_rate",
  "threshold": 0.5,
  "time_window": "1h",
  "min_samples": 5
}
```
含义：最近1小时内，至少5次执行，失败率超过50%则告警

**任务超时告警**：
```
{
  "metric": "timeout_rate",
  "threshold": 0.3,
  "time_window": "1h"
}
```
含义：最近1小时内，超时率超过30%则告警

**资源不足告警**：
```
{
  "metric": "cpu_usage",
  "threshold": 0.9,
  "duration": "5m"
}
```
含义：CPU使用率连续5分钟超过90%则告警

**3. 通知渠道配置**
```
{
  "email": ["admin@example.com", "ops@example.com"],
  "dingtalk": ["https://oapi.dingtalk.com/robot/send?access_token=xxx"],
  "wechat": ["https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"],
  "sms": ["13800138000"]
}
```

**4. 通知用户配置**
```
{
  "user_ids": [1, 2, 3],
  "roles": ["OWNER", "ADMIN"]
}
```
含义：通知指定用户ID，或通知具有特定角色的所有用户

**5. 告警静默期**
- 默认静默期：1小时
- 同一规则在静默期内不重复告警
- 使用Redis记录最后告警时间：
  - key：`alert:rule:{rule_id}:last_trigger`
  - value：timestamp
  - TTL：silence_period

#### 异常场景
- 规则名称重复：返回"规则名称已存在"
- 条件配置不合法：返回具体的JSON验证错误
- 通知渠道配置错误：返回"通知渠道配置不正确"

### 11.3 告警检测

#### 检测时机
- 定时检测：每分钟扫描一次
- 事件触发：任务失败、超时时立即检测

#### 检测流程

**步骤1：查询启用的告警规则**
- status = 1
- 按target_type和target_id过滤

**步骤2：计算指标值**
- 根据rule_type和condition_config计算当前指标
- 示例：计算最近1小时的任务失败率
  ```
  SELECT 
    COUNT(*) FILTER (WHERE status='FAILED') / COUNT(*) as failure_rate
  FROM task_instance
  WHERE task_id = {target_id}
    AND created_at >= NOW() - INTERVAL 1 HOUR
  ```

**步骤3：判断是否触发**
- 如果指标值超过阈值，触发告警
- 检查静默期，如果在静默期内，跳过

**步骤4：记录性能快照**
- 插入metric_snapshot记录
- 包含触发时的详细指标数据

**步骤5：发送通知**
- 根据notification_channels发送通知
- 遍历所有渠道，异步发送

**步骤6：更新静默期**
- 在Redis记录本次告警时间
- 设置TTL为静默期时长

#### 指标计算逻辑

**任务失败率**：
```
失败率 = 失败次数 / 总次数
时间窗口：最近N小时
最小样本数：至少M次执行
```

**任务超时率**：
```
超时率 = 超时次数 / 总次数
时间窗口：最近N小时
```

**资源使用率**：
```
CPU使用率 = used_cpu / max_cpu
持续时间：连续N分钟超过阈值
```

**任务积压**：
```
积压数 = PENDING状态的任务数
阈值：超过N个任务
```

### 11.4 告警通知

#### 邮件通知

**邮件模板**：
```
主题：【告警】{rule_name}
正文：
租户：{tenant_name}
项目：{project_name}（如果有）
任务：{task_name}（如果有）
告警规则：{rule_name}
告警内容：{condition_description}
当前指标：{metric_value}
阈值：{threshold}
时间：{trigger_time}
查看详情：{detail_url}
```

**发送逻辑**：
- 使用SMTP协议发送
- 配置SMTP服务器地址、端口、账号、密码
- 使用HTML格式，支持样式美化
- 发送失败重试3次

#### 钉钉通知

**消息格式**：
```
{
  "msgtype": "markdown",
  "markdown": {
    "title": "【告警】任务失败率超阈值",
    "text": "### 🚨 任务失败率告警\n\n" +
            "- **租户**: {tenant_name}\n" +
            "- **项目**: {project_name}\n" +
            "- **任务**: {task_name}\n" +
            "- **失败率**: {failure_rate}%\n" +
            "- **阈值**: {threshold}%\n" +
            "- **时间**: {trigger_time}\n\n" +
            "[查看详情]({detail_url})"
  }
}
```

**发送逻辑**：
- 使用Webhook URL
- POST请求，JSON格式
- 支持@指定用户（需配置手机号）

#### 企业微信通知

**消息格式**：
```
{
  "msgtype": "markdown",
  "markdown": {
    "content": "## 【告警】{rule_name}\n\n" +
               "> 租户：{tenant_name}\n" +
               "> 任务：{task_name}\n" +
               "> 指标：{metric_value}\n" +
               "> 阈值：{threshold}\n\n" +
               "[查看详情]({detail_url})"
  }
}
```

#### 短信通知（可选）

**短信内容**：
```
【调度平台告警】{tenant_name}的{task_name}任务失败率达{failure_rate}%，
超过阈值{threshold}%，请及时处理。详情：{short_url}
```

**限制**：
- 短信内容限制70字符
- 使用短链接服务
- 只在紧急情况使用（如多次失败）

#### 通知失败处理

**失败场景**：
- 邮件服务器不可达
- Webhook URL无效
- 网络超时

**处理策略**：
- 记录失败日志
- 重试3次，间隔10秒
- 最终失败后，标记为通知失败
- 管理员可以在告警历史中查看失败记录

### 11.5 性能监控

#### 监控指标

**系统级指标**：
- 调度器CPU使用率
- 调度器内存使用量
- 数据库连接池使用情况
- Redis连接数
- 消息队列堆积量

**业务级指标**：
- 每分钟调度任务数（TPS）
- 平均调度延迟
- 任务成功率
- 任务平均执行时长
- 资源利用率

**资源级指标**：
- 节点CPU使用率
- 节点内存使用率
- 节点GPU使用率
- 节点任务数

#### 指标采集

**采集方式**：
- 系统指标：使用JMX或Actuator
- 业务指标：从数据库统计
- 资源指标：从心跳数据计算

**采集频率**：
- 系统指标：每30秒
- 业务指标：每1分钟
- 资源指标：每5分钟

**存储**：
- 写入metric_snapshot表
- 或使用时序数据库（InfluxDB/Prometheus）

#### 监控面板

**展示维度**：
- 实时监控：当前状态和关键指标
- 趋势分析：历史数据曲线图
- 对比分析：不同租户、项目的对比

**图表类型**：
- 折线图：趋势展示（资源使用率、任务数量）
- 柱状图：对比展示（各状态任务数量）
- 饼图：占比展示（各租户资源占比）
- 热力图：时间分布（任务执行时间分布）

---

## 12. 分布式一致性保障

### 12.1 业务概述
在分布式环境下，保证数据一致性、避免重复调度、防止资源超卖。

### 12.2 分布式锁

#### 使用场景

**场景1：Leader选举**
- 多个调度器实例竞争Leader
- 只有Leader可以执行调度任务
- 避免重复调度

**场景2：任务调度互斥**
- 防止同一任务被多个调度器同时调度
- 锁粒度：任务实例级别

**场景3：资源分配互斥**
- 多个任务竞争同一资源节点
- 锁粒度：资源节点级别

**场景4：配额更新互斥**
- 多个任务同时扣减租户配额
- 锁粒度：租户级别

#### 分布式锁实现

**使用Redis实现**：
- 基于Redisson框架
- 支持自动续期（Watch Dog机制）
- 支持可重入

**锁key命名规范**：
- Leader锁：`scheduler:leader:lock`
- 任务实例锁：`scheduler:instance:{instance_id}:lock`
- 资源节点锁：`scheduler:node:{node_id}:lock`
- 租户配额锁：`scheduler:quota:{tenant_id}:lock`

**锁超时时间**：
- Leader锁：30秒（自动续期）
- 任务实例锁：10秒
- 资源节点锁：5秒
- 租户配额锁：3秒

**使用流程**：
1. 尝试获取锁，设置超时时间
2. 如果获取失败，等待或放弃
3. 获取成功后，执行业务逻辑
4. 业务执行完成，释放锁
5. 异常情况，锁自动过期释放

**异常处理**：
- 获取锁超时：返回错误，稍后重试
- 业务执行超时：Watch Dog自动续期
- 锁释放失败：依赖自动过期

### 12.3 乐观锁

#### 使用场景

**场景1：任务配置更新**
- 多个用户同时修改任务配置
- 使用version字段防止覆盖

**场景2：任务实例状态更新**
- 调度器和回调接口同时更新状态
- 使用version字段防止状态错乱

**场景3：资源节点资源更新**
- 多个任务同时分配资源
- 使用version字段防止超卖

**场景4：租户配额更新**
- 多个任务同时扣减配额
- 使用version字段防止超卖

#### 乐观锁流程

**步骤1：查询当前版本**
- SELECT * FROM table WHERE id = ? 
- 获取当前version值

**步骤2：执行业务逻辑**
- 在内存中计算新值

**步骤3：更新并检查版本**
- UPDATE table SET field = ?, version = version + 1
- WHERE id = ? AND version = ?
- 检查影响行数

**步骤4：处理冲突**
- 如果影响行数为0，说明版本冲突
- 重新查询，重试业务逻辑
- 最多重试3次

**示例流程（资源分配）**：
1. 查询资源节点，获取available_cpu=10, version=5
2. 计算：new_available_cpu = 10 - 2 = 8
3. 更新：
   ```
   UPDATE resource_node 
   SET available_cpu = 8, version = 6
   WHERE id = 1 AND version = 5
   ```
4. 如果影响行数=1，分配成功
5. 如果影响行数=0，有并发冲突，重新查询后重试

### 12.4 幂等性设计

#### 场景1：任务实例提交

**问题**：
- API重试导致重复提交
- 同一任务被提交多次

**解决方案**：
- 使用instance_code作为幂等键
- instance_code在数据库中唯一约束
- 重复提交时，捕获唯一约束异常
- 返回已存在的任务实例

**实现逻辑**：
1. 生成instance_code
2. 尝试插入task_instance
3. 如果插入成功，返回新实例
4. 如果唯一约束冲突，查询已存在的实例并返回

#### 场景2：任务重复调度

**问题**：
- 多个调度器同时调度同一任务
- 任务被执行多次

**解决方案**：
- 使用分布式锁
- 锁key：`scheduler:instance:{instance_id}:lock`
- 只有获取到锁的调度器可以调度

**实现逻辑**：
1. 获取任务实例锁
2. 如果获取失败，跳过
3. 获取成功，检查状态是否为PENDING/READY
4. 如果是，执行调度；如果不是，释放锁

#### 场景3：任务重复回调

**问题**：
- Agent多次报告任务完成
- 状态被重复更新

**解决方案**：
- 检查当前状态
- 只允许从RUNNING状态转换到终态
- 如果已经是终态，直接返回成功（幂等）

**实现逻辑**：
1. 查询任务实例状态
2. 如果状态为SUCCESS/FAILED/CANCELLED，直接返回成功
3. 如果状态为RUNNING，执行状态更新
4. 使用乐观锁更新，防止并发

### 12.5 任务去重

#### 场景：工作流调度去重

**问题**：
- CRON表达式在整分钟触发
- 多个调度器可能同时创建工作流实例

**解决方案**：
- 使用Redis Set记录已调度的工作流
- key：`scheduler:workflow:scheduled:{date}`
- value：workflow_id集合
- TTL：24小时

**实现逻辑**：
1. 检查Redis Set，SISMEMBER判断workflow_id是否存在
2. 如果存在，说明已调度，跳过
3. 如果不存在，执行调度，并SADD添加到Set
4. 整个过程使用Lua脚本保证原子性

**Lua脚本逻辑**：
```
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
  return 0  -- 已存在
else
  redis.call('SADD', KEYS[1], ARGV[1])
  redis.call('EXPIRE', KEYS[1], 86400)
  return 1  -- 成功添加
end
```

### 12.6 事务保障

#### 场景1：租户创建

**要求**：
- 租户创建和成员添加必须原子
- 租户创建和配额创建必须原子

**实现**：
- 使用数据库事务（@Transactional）
- 所有操作在同一事务中
- 任一步骤失败，全部回滚

#### 场景2：工作流实例创建

**要求**：
- 工作流实例创建和任务实例批量创建必须原子

**实现**：
- 使用数据库事务
- 批量插入task_instance
- 失败则回滚

#### 场景3：资源分配与配额扣减

**要求**：
- 资源节点资源扣减和配额扣减必须原子
- 任一失败，全部回滚

**实现**：
- 使用数据库事务
- 先更新resource_node（乐观锁）
- 再更新resource_quota（乐观锁）
- 任一更新失败，回滚事务

**注意事项**：
- 乐观锁失败不自动回滚
- 需要手动检测并抛出异常
- 触发事务回滚

---

## 📌 总结

本文档详细描述了分布式调度系统的12个核心业务模块的逻辑，涵盖：

✅ **用户与权限**：注册、登录、认证、授权  
✅ **租户管理**：多租户隔离、成员管理、角色权限  
✅ **项目与任务**：任务定义、参数配置、版本控制  
✅ **依赖与DAG**：任务依赖、环路检测、拓扑排序  
✅ **任务执行**：实例创建、状态流转、重试逻辑  
✅ **工作流**：DAG定义、批量调度、状态聚合  
✅ **调度器**：主循环、依赖检查、资源分配、任务提交  
✅ **资源管理**：节点注册、配额管理、调度策略  
✅ **日志管理**：日志收集、存储、查询、清理  
✅ **监控告警**：规则配置、指标计算、通知发送  
✅ **一致性**：分布式锁、乐观锁、幂等性、事务

每个业务模块都包含：
- 业务概述和目标
- 详细的处理流程
- 业务规则和约束
- 边界条件和异常处理
- 状态流转和数据一致性要求

文档可直接用于：
- 开发人员理解业务逻辑
- 编写详细设计文档
- 代码实现参考
- 测试用例设计
- 技术评审和讨论

---

**文档版本**: v1.0  
**创建时间**: 2026-04-03  
**适用范围**: 分布式轻量级调度系统 V1
