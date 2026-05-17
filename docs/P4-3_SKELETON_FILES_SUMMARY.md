# P4-3 工作流实例执行 - 文件骨架创建说明

本文档说明根据 `P4-3_WORKFLOW_INSTANCE_EXECUTION_DESIGN.md` 设计文档创建的所有文件骨架。

## 📋 创建的文件清单

### 1. 实体类（Entity）

#### ✅ WorkflowTaskInstance.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/entity/WorkflowTaskInstance.java`
- **功能**: 工作流任务实例实体类，对应 `workflow_task_instance` 表
- **说明**: 记录工作流实例中每个任务的执行状态、时间、结果等信息

#### ⚠️ WorkflowInstance.java（已存在）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/entity/WorkflowInstance.java`
- **说明**: 该文件已存在，保持现有实现不变

---

### 2. 枚举类（Enum/Constant）

#### ✅ WorkflowInstanceStatus.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/constant/WorkflowInstanceStatus.java`
- **功能**: 工作流实例状态枚举
- **值**: PENDING, PREPARING, RUNNING, PAUSED, SUCCESS, FAILED, PARTIAL_SUCCESS, CANCELLED

#### ✅ FailureStrategy.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/constant/FailureStrategy.java`
- **功能**: 失败处理策略枚举
- **值**: STOP_ON_FAILURE, CONTINUE_ON_FAILURE

#### ✅ TriggerType.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/constant/TriggerType.java`
- **功能**: 触发类型枚举
- **值**: MANUAL, SCHEDULED, API

#### ✅ TaskInstanceStatus.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/constant/TaskInstanceStatus.java`
- **功能**: 工作流任务实例状态枚举
- **值**: PENDING, RUNNING, SUCCESS, FAILED, SKIPPED

---

### 3. Mapper接口

#### ✅ WorkflowInstanceMapper.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/mapper/WorkflowInstanceMapper.java`
- **功能**: 工作流实例数据访问接口
- **方法**:
  - `incrementCompletedTasks()` - 增加已完成任务数
  - `incrementFailedTasks()` - 增加失败任务数
  - `selectByWorkflowId()` - 根据工作流ID查询
  - `selectByStatus()` - 根据状态查询
  - `countRunningInstances()` - 统计运行中的实例

#### ✅ WorkflowTaskInstanceMapper.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/mapper/WorkflowTaskInstanceMapper.java`
- **功能**: 工作流任务实例数据访问接口
- **方法**:
  - `selectByInstanceId()` - 查询实例的所有任务
  - `selectByInstanceIdAndLayer()` - 查询指定层级的任务
  - `selectRunningTasksByInstance()` - 查询运行中的任务
  - `selectByInstanceIdAndTaskName()` - 根据名称查询任务
  - `batchUpdateStatus()` - 批量更新状态

---

### 4. Service接口及实现

#### ✅ WorkflowInstanceService.java（接口）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/WorkflowInstanceService.java`
- **功能**: 工作流实例服务接口
- **核心方法**:
  - `createWorkflowInstance()` - 创建工作流实例
  - `getWorkflowInstance()` - 查询实例
  - `rerunWorkflowInstance()` - 重新运行
  - `deleteWorkflowInstance()` - 删除实例

#### ✅ WorkflowInstanceServiceImpl.java（实现）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/impl/WorkflowInstanceServiceImpl.java`
- **说明**: 实现类骨架，包含TODO标记的待实现方法

#### ✅ WorkflowExecutor.java（接口）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/WorkflowExecutor.java`
- **功能**: 工作流执行引擎接口
- **核心方法**:
  - `executeWorkflowInstance()` - 执行工作流实例
  - `executeWorkflowInstanceAsync()` - 异步执行

#### ✅ WorkflowExecutorImpl.java（实现）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/impl/WorkflowExecutorImpl.java`
- **说明**: 实现类骨架，负责按层并行执行任务

#### ✅ WorkflowControlService.java（接口）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/WorkflowControlService.java`
- **功能**: 工作流控制服务接口
- **核心方法**:
  - `pauseWorkflowInstance()` - 暂停
  - `resumeWorkflowInstance()` - 恢复
  - `cancelWorkflowInstance()` - 取消
  - `retryFailedTasks()` - 重试失败任务

#### ✅ WorkflowControlServiceImpl.java（实现）
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/service/workflow/impl/WorkflowControlServiceImpl.java`
- **说明**: 实现类骨架

---

### 5. DTO/VO

#### ✅ WorkflowInstanceCreateRequest.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/workflow/WorkflowInstanceCreateRequest.java`
- **功能**: 创建工作流实例请求DTO

#### ✅ WorkflowInstanceVO.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/workflow/WorkflowInstanceVO.java`
- **功能**: 工作流实例详情VO

#### ✅ WorkflowTaskInstanceVO.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/workflow/WorkflowTaskInstanceVO.java`
- **功能**: 工作流任务实例VO

#### ✅ WorkflowProgressVO.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/workflow/WorkflowProgressVO.java`
- **功能**: 工作流实例进度VO

#### ✅ LayerProgressVO.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/model/dto/workflow/LayerProgressVO.java`
- **功能**: 层级执行进度VO

---

### 6. Controller

#### ✅ WorkflowInstanceController.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/controller/WorkflowInstanceController.java`
- **功能**: 工作流实例REST API
- **端点**:
  - `POST /api/workflow/instance/execute` - 创建并执行
  - `GET /api/workflow/instance/{id}` - 查询详情
  - `GET /api/workflow/instance/list` - 查询列表
  - `POST /api/workflow/instance/{id}/pause` - 暂停
  - `POST /api/workflow/instance/{id}/resume` - 恢复
  - `POST /api/workflow/instance/{id}/cancel` - 取消
  - `POST /api/workflow/instance/{id}/rerun` - 重新运行
  - `DELETE /api/workflow/instance/{id}` - 删除

#### ✅ WorkflowInstanceMonitorController.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/controller/WorkflowInstanceMonitorController.java`
- **功能**: 工作流实例监控API
- **端点**:
  - `GET /api/workflow/instance/{id}/progress` - 获取实时进度
  - `GET /api/workflow/instance/{id}/tasks` - 获取任务列表
  - `GET /api/workflow/instance/{id}/tasks/{taskId}` - 获取任务详情
  - `GET /api/workflow/instance/{id}/execution-plan` - 获取执行计划
  - `GET /api/workflow/instance/{id}/timeline` - 获取执行时间线

---

### 7. 配置类

#### ✅ WorkflowExecutorConfig.java
- **路径**: `src/main/java/com/imperium/distributed_lite_scheduler_v1/config/WorkflowExecutorConfig.java`
- **功能**: 工作流执行器线程池配置
- **Bean**:
  - `workflowExecutorThreadPool` - 任务并行执行线程池
  - `workflowInstanceAsyncExecutor` - 实例异步执行线程池

---

### 8. MyBatis Mapper XML

#### ✅ WorkflowInstanceMapper.xml
- **路径**: `src/main/resources/mapper/WorkflowInstanceMapper.xml`
- **功能**: WorkflowInstanceMapper的SQL映射文件

#### ✅ WorkflowTaskInstanceMapper.xml
- **路径**: `src/main/resources/mapper/WorkflowTaskInstanceMapper.xml`
- **功能**: WorkflowTaskInstanceMapper的SQL映射文件

---

### 9. SQL脚本

#### ✅ workflow_instance.sql
- **路径**: `src/main/resources/sql/workflow_instance.sql`
- **功能**: 数据库表结构定义
- **内容**:
  - `workflow_instance` 表结构（如果不存在则创建）
  - `workflow_task_instance` 表结构
  - 索引定义
  - 示例数据（注释掉）

---

## 🎯 核心设计原则

1. **以现有实现为准**: 已存在的 `WorkflowInstance` 实体保持不变
2. **只创建骨架**: 所有实现类只包含方法签名和TODO注释，不包含实际业务逻辑
3. **完整注释**: 每个类、方法都有详细的JavaDoc注释说明功能和职责
4. **接口分离**: Service层分离为接口和实现类，便于后续扩展

---

## 📝 后续实现步骤

### 第一步：数据库初始化
1. 执行 `src/main/resources/sql/workflow_instance.sql`
2. 验证表结构是否创建成功

### 第二步：实现核心服务
1. 实现 `WorkflowInstanceServiceImpl.createWorkflowInstance()`
2. 实现 `WorkflowExecutorImpl.executeWorkflowInstance()`
3. 实现 `WorkflowControlServiceImpl` 的控制方法

### 第三步：实现Mapper查询
1. 完善 `WorkflowInstanceMapper` 的查询方法
2. 完善 `WorkflowTaskInstanceMapper` 的查询方法

### 第四步：实现Controller
1. 实现 `WorkflowInstanceController` 的各个端点
2. 实现 `WorkflowInstanceMonitorController` 的监控端点
3. 添加统一的异常处理和参数验证

### 第五步：测试
1. 编写单元测试
2. 编写集成测试
3. 验证并发执行逻辑

---

## ⚠️ 注意事项

1. **线程池参数**: `WorkflowExecutorConfig` 中的线程池参数需要根据实际业务场景调整
2. **JSON序列化**: 需要配置 `ObjectMapper` bean用于执行计划的序列化/反序列化
3. **事务管理**: Service层的关键方法已添加 `@Transactional` 注解
4. **异常处理**: 需要定义统一的异常类和异常处理器
5. **权限控制**: Controller层需要集成权限验证

---

## 🔗 依赖关系

```
Controller
    ↓
Service (Interface)
    ↓
Service (Impl) → Mapper → Database
    ↓
Entity/DTO/VO
```

---

## 📊 文件统计

- **Entity**: 1个（新增）+ 1个（已存在）
- **Enum/Constant**: 4个
- **Mapper**: 2个
- **Service**: 3个接口 + 3个实现
- **Controller**: 2个
- **DTO/VO**: 5个
- **Config**: 1个
- **Mapper XML**: 2个
- **SQL脚本**: 1个

**总计**: 24个文件

---

## ✅ 完成状态

所有文件骨架已创建完成，可以开始进行实际业务逻辑的开发。

每个文件都包含：
- ✅ 完整的类结构
- ✅ 方法签名
- ✅ 详细的注释
- ✅ TODO标记（标识待实现的部分）

---

## 📚 相关文档

- 设计文档: `docs/P4-3_WORKFLOW_INSTANCE_EXECUTION_DESIGN.md`
- 本说明: `docs/P4-3_SKELETON_FILES_SUMMARY.md`
