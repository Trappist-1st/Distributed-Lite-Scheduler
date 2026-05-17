# P4-1: 工作流定义与解析 - 代码骨架

## 📁 文件结构

根据P4-1设计稿生成的代码文件，按功能模块组织：

```
src/main/java/com/imperium/distributed_lite_scheduler_v1/
├── model/
│   ├── entity/
│   │   └── Workflow.java                    # ✅ 已存在（未修改）
│   │
│   └── dto/workflow/                        # 工作流相关DTO（独立子目录）
│       ├── WorkflowDAG.java                 # DAG定义
│       ├── WorkflowTask.java                # 任务定义
│       ├── WorkflowDependency.java          # 依赖关系
│       ├── RetryPolicy.java                 # 重试策略
│       ├── WorkflowCreateRequest.java       # 创建请求
│       ├── WorkflowUpdateRequest.java       # 更新请求
│       └── WorkflowVO.java                  # 视图对象
│
├── mapper/
│   └── WorkflowMapper.java                  # 工作流Mapper
│
├── service/workflow/                        # 工作流服务（独立子目录）
│   ├── WorkflowService.java                 # 服务接口
│   └── impl/
│       └── WorkflowServiceImpl.java         # 服务实现
│
└── controller/
    └── WorkflowController.java              # 工作流Controller

docs/sql/
└── P4-1_workflow_schema.sql                 # 数据库表结构参考（表已存在）
```

## ⚠️ 重要说明

### Workflow实体适配
代码已适配现有的Workflow实体，字段映射关系：

| 实体字段 | DTO/VO字段 | 说明 |
|---------|-----------|------|
| workflowName | workflowName | 工作流名称 |
| workflowCode | workflowCode | 工作流编码 |
| status (Integer) | status (Integer) | 0-禁用，1-正常 |
| version | version | 乐观锁版本号 |
| creatorUserId | creatorUserId | 创建者用户ID |
| deleted | - | 逻辑删除标记（自动处理）|

### 数据库说明
- ✅ `workflow`表已存在于数据库中
- ⚠️ 请勿执行`P4-1_workflow_schema.sql`（仅供参考）
- 表结构包含调度相关字段（scheduleType、cronExpression等）

## 🎯 实现顺序建议

建议按照以下顺序实现各个功能模块：

### 1️⃣ 确认现有表结构（2分钟）
```sql
-- 查看workflow表结构
DESC workflow;

-- 确认dag_json字段存在且类型正确
SHOW CREATE TABLE workflow;
```

### 2️⃣ 基础模型（已完成）
- ✅ Workflow.java（原有实体，未修改）
- ✅ WorkflowDAG.java 及相关DTO（已适配）

### 3️⃣ Mapper层（10分钟）
**文件**: `WorkflowMapper.java`

**待实现**:
- `selectByProjectIdAndStatus()` - 根据项目ID和状态查询

**提示**: 使用MyBatis-Plus的LambdaQueryWrapper
```java
@Select("SELECT * FROM workflow WHERE project_id = #{projectId} " +
        "AND status = #{status} AND deleted = 0 ORDER BY created_at DESC")
List<Workflow> selectByProjectIdAndStatus(@Param("projectId") Long projectId, 
                                          @Param("status") Integer status);
```

### 4️⃣ Service层 - parseDAG()（20分钟）
**文件**: `WorkflowServiceImpl.java`

**待实现**: `parseDAG()` 方法

**步骤**:
```java
1. 使用ObjectMapper解析JSON字符串
   WorkflowDAG dag = objectMapper.readValue(dagJson, WorkflowDAG.class);

2. 校验必填字段
   - tasks不能为空
   - 如果dependencies为null，初始化为空列表

3. 返回DAG对象
```

### 5️⃣ Service层 - validateDAG()（30分钟）
**文件**: `WorkflowServiceImpl.java`

**待实现**: `validateDAG()` 方法

**步骤**:
```java
1. 验证任务名称唯一性
   Set<String> taskNames = new HashSet<>();
   for (WorkflowTask task : dag.getTasks()) {
       if (!taskNames.add(task.getName())) {
           throw new IllegalArgumentException("任务名称重复: " + task.getName());
       }
   }

2. 验证依赖引用的任务存在
   for (WorkflowDependency dep : dag.getDependencies()) {
       if (!taskNames.contains(dep.getFrom())) {
           throw new IllegalArgumentException("上游任务不存在: " + dep.getFrom());
       }
       if (!taskNames.contains(dep.getTo())) {
           throw new IllegalArgumentException("下游任务不存在: " + dep.getTo());
       }
   }

3. 调用detectCycle()检测循环依赖
```

### 6️⃣ Service层 - detectCycle()（40分钟）⚠️ 重点
**文件**: `WorkflowServiceImpl.java`

**待实现**: `detectCycle()` 和 `dfsHasCycle()` 方法

**算法**: DFS + 三色标记
- 白色(0): 未访问
- 灰色(1): 正在访问（在DFS栈中）
- 黑色(2): 已访问完成

**详细代码已在ServiceImpl中作为注释提供**

### 7️⃣ Service层 - CRUD方法（30分钟）
**文件**: `WorkflowServiceImpl.java`

**待实现**:
- `createWorkflow()` - 创建工作流
  - 注意字段映射：request.getWorkflowName() -> workflow.setWorkflowName()
  - status默认设置为1（正常）
  - createdAt/updatedAt会自动填充
- `updateWorkflow()` - 更新工作流
  - version字段是乐观锁，MyBatis-Plus会自动处理
- `deleteWorkflow()` - 删除工作流
  - 由于@TableLogic注解，这是逻辑删除
- `getById()` - 查询工作流
- `toVO()` - 转换为VO
  - 注意字段映射
  - 需要解析dagJson并设置dag字段
- `listWorkflows()` - 查询列表

### 8️⃣ Controller层（15分钟）
**文件**: `WorkflowController.java`

**待实现**: 所有接口方法（已提供详细TODO注释）

## 🧪 测试建议

### 单元测试
创建 `WorkflowServiceTest.java`:
```java
@Test
public void testParseDAG() {
    String validJson = "...";
    WorkflowDAG dag = workflowService.parseDAG(validJson);
    assertNotNull(dag);
}

@Test
public void testDetectCycle() {
    // 测试有环和无环的情况
}

@Test
public void testValidateDAG() {
    // 测试重复任务名、不存在的依赖等
}
```

### 集成测试
使用Postman或curl测试API:
```bash
# 创建工作流
POST /api/workflow
{
  "projectId": 1,
  "workflowName": "测试工作流",
  "workflowCode": "test_workflow",
  "dagJson": "{...}",
  "scheduleType": "MANUAL"
}

# 查询工作流
GET /api/workflow/1

# 查询列表
GET /api/workflow/list?projectId=1&status=1
```

## 📚 参考资料

1. **设计文档**: `docs/P4-1_WORKFLOW_DEFINITION_AND_PARSING_DESIGN.md`
2. **循环检测算法**: 第3.3节 - DFS + 三色标记
3. **DAG JSON格式**: 第3.2节 或查看 `P4-1_workflow_schema.sql`
4. **API设计**: 第6节

## ⚠️ 注意事项

1. **字段映射**: 注意Workflow实体的字段名（workflowName vs name）
2. **状态类型**: status是Integer类型（0-禁用，1-正常），不是枚举
3. **乐观锁**: version字段由MyBatis-Plus自动管理，更新时会自动+1
4. **逻辑删除**: @TableLogic注解会将delete操作转为update deleted=1
5. **JSON解析**: 使用Jackson的ObjectMapper，需要处理JsonProcessingException
6. **循环检测**: 这是核心算法，建议单独测试
7. **事务管理**: createWorkflow和updateWorkflow需要@Transactional
8. **异常处理**: 所有异常都应该有清晰的错误消息
9. **日志记录**: 关键操作要记录日志（log.info/log.error）

## 🚀 下一步

完成P4-1后，可以继续实现：
- **P4-2**: 拓扑排序引擎（基于P4-1的DAG进行拓扑排序）
- **P4-3**: 工作流实例执行（基于拓扑排序结果执行工作流）
- **P4-4**: 条件分支支持（进阶特性）

## 💡 Tips

- 循环检测算法建议先在纸上画图理解
- 可以先实现简单的CRUD，最后再实现循环检测
- 测试时使用简单的DAG（3-4个任务）
- 参考设计文档第9节的测试方案
- 现有的Workflow表已包含调度相关字段，未来可扩展定时调度功能

祝实现顺利！🎉
