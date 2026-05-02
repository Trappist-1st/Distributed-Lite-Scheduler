# P4-1: 工作流定义与解析设计稿

## 1. 文档目标

本文档详细设计工作流（Workflow）的定义、存储和解析机制，作为DAG工作流引擎的第一步，解决以下问题：

- 如何表达复杂的任务依赖关系
- 如何存储工作流定义
- 如何检测循环依赖（DAG合法性验证）
- 如何解析工作流JSON为可执行的图结构

**核心价值**：为后续的拓扑排序和并行执行提供可靠的数据基础，是工作流引擎的"大脑"。

---

## 2. 工作流核心概念

### 2.1 什么是工作流（Workflow）

**定义**：由多个任务（Task）及其依赖关系（Dependency）组成的有向无环图（DAG）。

**示例场景：数据ETL工作流**
```
┌─────────────────────────────────────────┐
│        数据处理工作流                     │
├─────────────────────────────────────────┤
│                                         │
│    ┌──────┐                            │
│    │ A: 提取│                            │
│    │原始数据│                            │
│    └───┬──┘                            │
│        │                                │
│    ┌───▼──┐      ┌──────┐             │
│    │ B: 清洗│ ───► │ D: 生成│             │
│    │ 数据  │      │ 报表  │             │
│    └───┬──┘      └──────┘             │
│        │                                │
│    ┌───▼──┐                            │
│    │ C: 转换│                            │
│    │ 数据  │                            │
│    └──────┘                            │
│                                         │
│  依赖关系：                              │
│  A → B → D                              │
│  A → B → C                              │
└─────────────────────────────────────────┘
```

**关键特性**：
- **有向性**：依赖关系有方向（A → B）
- **无环性**：不能出现循环依赖（A → B → C → A ❌）
- **并行性**：无依赖关系的任务可以并行执行（B和C可并行）

### 2.2 为什么需要工作流

**场景1：复杂数据处理**
```
单个任务：只能处理一个步骤
工作流：可以编排多个步骤，自动化整个流程
```

**场景2：机器学习训练**
```
数据预处理 → 特征工程 → 模型训练 → 模型评估 → 模型部署
```

**场景3：业务流程自动化**
```
订单创建 → 库存检查 → 支付处理 → 发货通知 → 数据归档
```

**价值**：
- ✅ **自动化**：一次定义，多次执行
- ✅ **可视化**：清晰的依赖关系
- ✅ **可维护**：修改工作流而非代码
- ✅ **并行性**：自动识别可并行任务

---

## 3. 数据模型设计

### 3.1 核心表结构

#### workflow表（工作流定义）
```sql
CREATE TABLE workflow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '工作流名称',
    project_id BIGINT NOT NULL COMMENT '所属项目',
    description TEXT COMMENT '工作流描述',
    
    -- DAG定义
    dag_json JSON NOT NULL COMMENT 'DAG定义(JSON格式)',
    
    -- 元数据
    version INT DEFAULT 1 COMMENT '版本号',
    status ENUM('DRAFT', 'ACTIVE', 'ARCHIVED') DEFAULT 'DRAFT' COMMENT '状态',
    
    -- 审计字段
    created_by BIGINT COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_project_id (project_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义表';
```

#### task_dependency表（任务依赖关系）
```sql
CREATE TABLE task_dependency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL COMMENT '工作流ID',
    
    -- 依赖关系
    from_task_name VARCHAR(100) NOT NULL COMMENT '上游任务名称',
    to_task_name VARCHAR(100) NOT NULL COMMENT '下游任务名称',
    
    -- 条件执行（可选）
    condition VARCHAR(500) COMMENT '执行条件(SpEL表达式)',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_workflow_id (workflow_id),
    INDEX idx_from_task (from_task_name),
    INDEX idx_to_task (to_task_name),
    
    UNIQUE KEY uk_workflow_dependency (workflow_id, from_task_name, to_task_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖关系表';
```

### 3.2 DAG JSON格式

**标准格式**：
```json
{
  "version": "1.0",
  "tasks": [
    {
      "name": "extract_data",
      "displayName": "提取原始数据",
      "type": "shell",
      "command": "python extract.py",
      "resourceRequirement": {
        "cpu": 2,
        "memory": 4096
      },
      "retryPolicy": {
        "maxAttempts": 3,
        "backoffMultiplier": 2
      }
    },
    {
      "name": "clean_data",
      "displayName": "清洗数据",
      "type": "python",
      "script": "clean.py",
      "resourceRequirement": {
        "cpu": 4,
        "memory": 8192
      }
    },
    {
      "name": "transform_data",
      "displayName": "转换数据",
      "type": "shell",
      "command": "python transform.py"
    },
    {
      "name": "generate_report",
      "displayName": "生成报表",
      "type": "python",
      "script": "report.py"
    }
  ],
  "dependencies": [
    {
      "from": "extract_data",
      "to": "clean_data"
    },
    {
      "from": "clean_data",
      "to": "transform_data"
    },
    {
      "from": "clean_data",
      "to": "generate_report"
    }
  ]
}
```

**字段说明**：
- `tasks`：任务列表
  - `name`：任务唯一标识（用于依赖引用）
  - `displayName`：显示名称
  - `type`：执行类型（shell/python/docker）
  - `command/script`：执行命令
  - `resourceRequirement`：资源需求
- `dependencies`：依赖关系列表
  - `from`：上游任务
  - `to`：下游任务

---

## 4. 核心功能实现

### 4.1 工作流CRUD

#### Entity定义
```java
@Data
@TableName("workflow")
public class Workflow {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    private Long projectId;
    private String description;
    
    // DAG定义（存储为JSON字符串）
    private String dagJson;
    
    private Integer version;
    
    @TableField(value = "status")
    private WorkflowStatus status;
    
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public enum WorkflowStatus {
    DRAFT,      // 草稿
    ACTIVE,     // 激活
    ARCHIVED    // 归档
}
```

#### Controller实现
```java
@RestController
@RequestMapping("/api/workflow")
@Slf4j
public class WorkflowController {
    
    @Autowired
    private WorkflowService workflowService;
    
    /**
     * 创建工作流
     */
    @PostMapping
    public Result<Long> createWorkflow(@RequestBody @Valid WorkflowCreateRequest request) {
        log.info("创建工作流 name={}", request.getName());
        
        // 1. 解析DAG JSON
        WorkflowDAG dag = workflowService.parseDAG(request.getDagJson());
        
        // 2. 验证DAG合法性（无环性）
        workflowService.validateDAG(dag);
        
        // 3. 保存工作流
        Long workflowId = workflowService.createWorkflow(request);
        
        return Result.success(workflowId);
    }
    
    /**
     * 查询工作流详情
     */
    @GetMapping("/{id}")
    public Result<WorkflowVO> getWorkflow(@PathVariable Long id) {
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.error("工作流不存在");
        }
        
        WorkflowVO vo = workflowService.toVO(workflow);
        return Result.success(vo);
    }
    
    /**
     * 更新工作流
     */
    @PutMapping("/{id}")
    public Result<Void> updateWorkflow(
            @PathVariable Long id,
            @RequestBody @Valid WorkflowUpdateRequest request) {
        
        // 1. 验证DAG
        WorkflowDAG dag = workflowService.parseDAG(request.getDagJson());
        workflowService.validateDAG(dag);
        
        // 2. 更新工作流（增加版本号）
        workflowService.updateWorkflow(id, request);
        
        return Result.success();
    }
    
    /**
     * 删除工作流（软删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkflow(@PathVariable Long id) {
        workflowService.archiveWorkflow(id);
        return Result.success();
    }
    
    /**
     * 查询项目下的工作流列表
     */
    @GetMapping("/list")
    public Result<List<WorkflowVO>> listWorkflows(
            @RequestParam Long projectId,
            @RequestParam(required = false) WorkflowStatus status) {
        
        List<WorkflowVO> workflows = workflowService.listWorkflows(projectId, status);
        return Result.success(workflows);
    }
}
```

### 4.2 DAG JSON解析

```java
@Service
@Slf4j
public class WorkflowService {
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    @Autowired
    private ObjectMapper objectMapper; // Jackson
    
    /**
     * 解析DAG JSON为内存对象
     */
    public WorkflowDAG parseDAG(String dagJson) {
        try {
            WorkflowDAG dag = objectMapper.readValue(dagJson, WorkflowDAG.class);
            
            // 校验必填字段
            if (dag.getTasks() == null || dag.getTasks().isEmpty()) {
                throw new IllegalArgumentException("工作流至少包含一个任务");
            }
            
            if (dag.getDependencies() == null) {
                dag.setDependencies(new ArrayList<>()); // 允许无依赖关系
            }
            
            return dag;
            
        } catch (JsonProcessingException e) {
            log.error("解析DAG JSON失败", e);
            throw new IllegalArgumentException("DAG JSON格式错误: " + e.getMessage());
        }
    }
    
    /**
     * 验证DAG合法性
     */
    public void validateDAG(WorkflowDAG dag) {
        // 1. 验证任务名称唯一性
        Set<String> taskNames = new HashSet<>();
        for (WorkflowTask task : dag.getTasks()) {
            if (!taskNames.add(task.getName())) {
                throw new IllegalArgumentException("任务名称重复: " + task.getName());
            }
        }
        
        // 2. 验证依赖引用的任务存在
        for (WorkflowDependency dep : dag.getDependencies()) {
            if (!taskNames.contains(dep.getFrom())) {
                throw new IllegalArgumentException("依赖引用的上游任务不存在: " + dep.getFrom());
            }
            if (!taskNames.contains(dep.getTo())) {
                throw new IllegalArgumentException("依赖引用的下游任务不存在: " + dep.getTo());
            }
        }
        
        // 3. 检测循环依赖
        detectCycle(dag);
        
        log.info("DAG验证通过 taskCount={} dependencyCount={}", 
                dag.getTasks().size(), dag.getDependencies().size());
    }
}
```

### 4.3 循环依赖检测

**算法**：深度优先搜索（DFS） + 三色标记

**三色标记法**：
- **白色（0）**：未访问
- **灰色（1）**：正在访问（在DFS栈中）
- **黑色（2）**：已访问完成

**关键逻辑**：如果访问到灰色节点，说明存在环。

```java
/**
 * 检测循环依赖（DFS + 三色标记）
 */
private void detectCycle(WorkflowDAG dag) {
    // 1. 构建邻接表
    Map<String, List<String>> graph = new HashMap<>();
    for (WorkflowTask task : dag.getTasks()) {
        graph.put(task.getName(), new ArrayList<>());
    }
    for (WorkflowDependency dep : dag.getDependencies()) {
        graph.get(dep.getFrom()).add(dep.getTo());
    }
    
    // 2. 三色标记
    Map<String, Integer> color = new HashMap<>();
    for (String taskName : graph.keySet()) {
        color.put(taskName, 0); // 初始化为白色
    }
    
    // 3. DFS检测环
    for (String taskName : graph.keySet()) {
        if (color.get(taskName) == 0) { // 未访问
            if (dfsHasCycle(taskName, graph, color, new ArrayList<>())) {
                throw new IllegalArgumentException("检测到循环依赖，DAG无效");
            }
        }
    }
    
    log.info("循环依赖检测通过");
}

/**
 * DFS递归检测环
 */
private boolean dfsHasCycle(
        String node, 
        Map<String, List<String>> graph,
        Map<String, Integer> color,
        List<String> path) {
    
    // 标记为灰色（正在访问）
    color.put(node, 1);
    path.add(node);
    
    // 访问所有邻居
    for (String neighbor : graph.get(node)) {
        int neighborColor = color.get(neighbor);
        
        if (neighborColor == 1) {
            // 邻居是灰色，说明存在环
            path.add(neighbor);
            log.error("检测到循环依赖: {}", String.join(" → ", path));
            return true;
        }
        
        if (neighborColor == 0) {
            // 邻居是白色，递归访问
            if (dfsHasCycle(neighbor, graph, color, path)) {
                return true;
            }
        }
    }
    
    // 标记为黑色（访问完成）
    color.put(node, 2);
    path.remove(path.size() - 1);
    
    return false;
}
```

**测试用例**：
```java
@Test
public void testCycleDetection() {
    // 无环DAG
    WorkflowDAG validDAG = new WorkflowDAG();
    validDAG.setTasks(Arrays.asList(
        task("A"), task("B"), task("C")
    ));
    validDAG.setDependencies(Arrays.asList(
        dep("A", "B"),
        dep("B", "C")
    ));
    
    assertDoesNotThrow(() -> workflowService.validateDAG(validDAG));
    
    // 有环DAG
    WorkflowDAG cyclicDAG = new WorkflowDAG();
    cyclicDAG.setTasks(Arrays.asList(
        task("A"), task("B"), task("C")
    ));
    cyclicDAG.setDependencies(Arrays.asList(
        dep("A", "B"),
        dep("B", "C"),
        dep("C", "A") // 循环：A → B → C → A
    ));
    
    assertThrows(IllegalArgumentException.class, 
        () -> workflowService.validateDAG(cyclicDAG));
}
```

---

## 5. 数据模型（DTO）

```java
/**
 * 工作流DAG定义
 */
@Data
public class WorkflowDAG {
    private String version;
    private List<WorkflowTask> tasks;
    private List<WorkflowDependency> dependencies;
}

/**
 * 工作流任务定义
 */
@Data
public class WorkflowTask {
    private String name;              // 任务名称（唯一）
    private String displayName;       // 显示名称
    private String type;              // 类型：shell/python/docker
    private String command;           // Shell命令
    private String script;            // Python脚本
    private ResourceRequirement resourceRequirement; // 资源需求
    private RetryPolicy retryPolicy;  // 重试策略
    private Map<String, Object> params; // 参数
}

/**
 * 工作流依赖关系
 */
@Data
public class WorkflowDependency {
    private String from;    // 上游任务
    private String to;      // 下游任务
    private String condition; // 执行条件（可选）
}

/**
 * 资源需求
 */
@Data
public class ResourceRequirement {
    private Integer cpu;      // CPU核数
    private Integer memory;   // 内存（MB）
    private Integer gpu;      // GPU卡数（可选）
}

/**
 * 重试策略
 */
@Data
public class RetryPolicy {
    private Integer maxAttempts;      // 最大重试次数
    private Integer backoffMultiplier; // 退避倍数
}
```

---

## 6. API设计

### 6.1 创建工作流
```http
POST /api/workflow
Content-Type: application/json

{
  "name": "数据ETL工作流",
  "projectId": 100,
  "description": "每日数据处理流程",
  "dagJson": "{...}"
}

Response:
{
  "code": 200,
  "message": "success",
  "data": 12345  // 工作流ID
}
```

### 6.2 查询工作流详情
```http
GET /api/workflow/12345

Response:
{
  "code": 200,
  "data": {
    "id": 12345,
    "name": "数据ETL工作流",
    "projectId": 100,
    "status": "ACTIVE",
    "version": 2,
    "dag": {
      "tasks": [...],
      "dependencies": [...]
    },
    "createdAt": "2026-05-01T10:00:00"
  }
}
```

### 6.3 更新工作流
```http
PUT /api/workflow/12345
Content-Type: application/json

{
  "name": "数据ETL工作流_v2",
  "dagJson": "{...}"
}
```

### 6.4 归档工作流
```http
DELETE /api/workflow/12345

Response:
{
  "code": 200,
  "message": "工作流已归档"
}
```

### 6.5 查询工作流列表
```http
GET /api/workflow/list?projectId=100&status=ACTIVE

Response:
{
  "code": 200,
  "data": [
    {
      "id": 12345,
      "name": "数据ETL工作流",
      "status": "ACTIVE",
      "taskCount": 4,
      "createdAt": "2026-05-01T10:00:00"
    }
  ]
}
```

---

## 7. 错误处理

### 7.1 常见错误

| 错误场景 | 错误码 | 错误消息 | HTTP状态码 |
|---------|--------|---------|-----------|
| JSON格式错误 | 4001 | DAG JSON格式错误 | 400 |
| 任务名称重复 | 4002 | 任务名称重复: task_a | 400 |
| 依赖任务不存在 | 4003 | 依赖引用的任务不存在: task_x | 400 |
| 检测到循环依赖 | 4004 | 检测到循环依赖，DAG无效 | 400 |
| 工作流不存在 | 4041 | 工作流不存在 | 404 |
| 无权限操作 | 4031 | 无权限操作该工作流 | 403 |

### 7.2 错误响应格式
```json
{
  "code": 4004,
  "message": "检测到循环依赖，DAG无效",
  "data": {
    "cycle": ["A", "B", "C", "A"]
  }
}
```

---

## 8. 监控与日志

### 8.1 操作日志

```java
@Aspect
@Component
@Slf4j
public class WorkflowAuditAspect {
    
    @Around("@annotation(com.imperium.annotation.WorkflowAudit)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        Long userId = getCurrentUserId();
        String operation = getOperationName(pjp);
        Long workflowId = getWorkflowId(pjp);
        
        log.info("工作流操作 userId={} operation={} workflowId={}", 
                userId, operation, workflowId);
        
        try {
            Object result = pjp.proceed();
            log.info("工作流操作成功");
            return result;
        } catch (Exception e) {
            log.error("工作流操作失败", e);
            throw e;
        }
    }
}
```

### 8.2 监控指标

```java
@Component
public class WorkflowMetrics {
    
    @Autowired
    private MeterRegistry registry;
    
    private Counter createCounter;
    private Counter updateCounter;
    private Counter validationErrorCounter;
    
    @PostConstruct
    public void init() {
        createCounter = Counter.builder("workflow.create.count")
            .description("工作流创建次数")
            .register(registry);
        
        updateCounter = Counter.builder("workflow.update.count")
            .description("工作流更新次数")
            .register(registry);
        
        validationErrorCounter = Counter.builder("workflow.validation.error.count")
            .description("DAG验证失败次数")
            .register(registry);
    }
    
    public void recordCreate() {
        createCounter.increment();
    }
    
    public void recordValidationError() {
        validationErrorCounter.increment();
    }
}
```

---

## 9. 性能优化

### 9.1 DAG解析缓存

```java
@Service
public class WorkflowService {
    
    private final Cache<Long, WorkflowDAG> dagCache = 
        CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
    
    /**
     * 获取DAG（带缓存）
     */
    public WorkflowDAG getDAG(Long workflowId) {
        return dagCache.get(workflowId, () -> {
            Workflow workflow = workflowMapper.selectById(workflowId);
            return parseDAG(workflow.getDagJson());
        });
    }
    
    /**
     * 更新工作流后清除缓存
     */
    public void updateWorkflow(Long workflowId, WorkflowUpdateRequest request) {
        // 更新数据库
        workflowMapper.updateById(...);
        
        // 清除缓存
        dagCache.invalidate(workflowId);
    }
}
```

### 9.2 索引优化

```sql
-- 项目+状态查询
CREATE INDEX idx_project_status ON workflow(project_id, status);

-- 创建时间倒序查询
CREATE INDEX idx_created_at ON workflow(created_at DESC);
```

---

## 10. 测试方案

### 10.1 单元测试

```java
@SpringBootTest
@Slf4j
public class WorkflowServiceTest {
    
    @Autowired
    private WorkflowService workflowService;
    
    @Test
    public void testParseValidDAG() {
        String dagJson = """
            {
              "tasks": [
                {"name": "A", "type": "shell", "command": "echo A"},
                {"name": "B", "type": "shell", "command": "echo B"}
              ],
              "dependencies": [
                {"from": "A", "to": "B"}
              ]
            }
            """;
        
        WorkflowDAG dag = workflowService.parseDAG(dagJson);
        assertNotNull(dag);
        assertEquals(2, dag.getTasks().size());
        assertEquals(1, dag.getDependencies().size());
    }
    
    @Test
    public void testDetectCycle() {
        String dagJson = """
            {
              "tasks": [
                {"name": "A", "type": "shell", "command": "echo A"},
                {"name": "B", "type": "shell", "command": "echo B"}
              ],
              "dependencies": [
                {"from": "A", "to": "B"},
                {"from": "B", "to": "A"}
              ]
            }
            """;
        
        WorkflowDAG dag = workflowService.parseDAG(dagJson);
        
        assertThrows(IllegalArgumentException.class, 
            () -> workflowService.validateDAG(dag));
    }
    
    @Test
    public void testDuplicateTaskName() {
        String dagJson = """
            {
              "tasks": [
                {"name": "A", "type": "shell", "command": "echo A"},
                {"name": "A", "type": "shell", "command": "echo A"}
              ],
              "dependencies": []
            }
            """;
        
        WorkflowDAG dag = workflowService.parseDAG(dagJson);
        
        assertThrows(IllegalArgumentException.class, 
            () -> workflowService.validateDAG(dag));
    }
}
```

### 10.2 集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
public class WorkflowControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    public void testCreateWorkflow() throws Exception {
        String request = """
            {
              "name": "测试工作流",
              "projectId": 1,
              "dagJson": "{\\"tasks\\":[...], \\"dependencies\\":[...]}"
            }
            """;
        
        mockMvc.perform(post("/api/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isNumber());
    }
    
    @Test
    public void testCreateWorkflowWithCycle() throws Exception {
        String request = """
            {
              "name": "循环依赖工作流",
              "projectId": 1,
              "dagJson": "{\\"tasks\\":[...], \\"dependencies\\":[{\\"from\\":\\"A\\",\\"to\\":\\"B\\"},{\\"from\\":\\"B\\",\\"to\\":\\"A\\"}]}"
            }
            """;
        
        mockMvc.perform(post("/api/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(4004));
    }
}
```

---

## 11. 使用示例

### 11.1 示例：数据ETL工作流

```json
{
  "version": "1.0",
  "tasks": [
    {
      "name": "extract",
      "displayName": "提取原始数据",
      "type": "shell",
      "command": "python extract.py --source /data/raw --output /data/staging",
      "resourceRequirement": {
        "cpu": 2,
        "memory": 4096
      }
    },
    {
      "name": "clean",
      "displayName": "清洗数据",
      "type": "python",
      "script": "clean.py",
      "resourceRequirement": {
        "cpu": 4,
        "memory": 8192
      },
      "retryPolicy": {
        "maxAttempts": 3,
        "backoffMultiplier": 2
      }
    },
    {
      "name": "transform",
      "displayName": "转换数据",
      "type": "shell",
      "command": "python transform.py --input /data/staging --output /data/processed"
    },
    {
      "name": "load",
      "displayName": "加载到数据仓库",
      "type": "shell",
      "command": "python load.py --target mysql://warehouse/db"
    }
  ],
  "dependencies": [
    {"from": "extract", "to": "clean"},
    {"from": "clean", "to": "transform"},
    {"from": "transform", "to": "load"}
  ]
}
```

### 11.2 示例：并行训练工作流

```json
{
  "version": "1.0",
  "tasks": [
    {
      "name": "data_prep",
      "displayName": "数据准备",
      "type": "python",
      "script": "prepare_data.py"
    },
    {
      "name": "train_model_a",
      "displayName": "训练模型A",
      "type": "docker",
      "image": "tensorflow:latest",
      "command": "python train.py --model a",
      "resourceRequirement": {
        "cpu": 8,
        "memory": 16384,
        "gpu": 1
      }
    },
    {
      "name": "train_model_b",
      "displayName": "训练模型B",
      "type": "docker",
      "image": "pytorch:latest",
      "command": "python train.py --model b",
      "resourceRequirement": {
        "cpu": 8,
        "memory": 16384,
        "gpu": 1
      }
    },
    {
      "name": "ensemble",
      "displayName": "模型集成",
      "type": "python",
      "script": "ensemble.py"
    }
  ],
  "dependencies": [
    {"from": "data_prep", "to": "train_model_a"},
    {"from": "data_prep", "to": "train_model_b"},
    {"from": "train_model_a", "to": "ensemble"},
    {"from": "train_model_b", "to": "ensemble"}
  ]
}
```

**说明**：`train_model_a`和`train_model_b`可以并行执行（无依赖关系）。

---

## 12. 常见问题

### Q1: 如何修改已激活的工作流？

**答**：采用版本控制机制
1. 创建新版本（version + 1）
2. 修改DAG定义
3. 保存为新版本
4. 旧版本仍可执行（向后兼容）

### Q2: DAG JSON存储在数据库还是文件系统？

**答**：推荐存储在数据库（JSON字段）
- **优点**：支持事务、易于查询、备份简单
- **缺点**：大DAG可能占用空间
- **优化**：超大DAG（>1MB）可存储到OSS，数据库存储URL

### Q3: 如何处理复杂的条件依赖？

**答**：在P4-4中实现（条件分支支持）
- 使用SpEL表达式
- 例如：`"condition": "${task_a.output.status == 'success'}"`

### Q4: 循环依赖检测的时间复杂度？

**答**：O(V + E)
- V：任务数量
- E：依赖关系数量
- 大部分工作流：V < 100, E < 200，性能无忧

---

## 13. 后续优化方向

### 13.1 P4-2: 拓扑排序引擎
- 基于DAG实现拓扑排序
- 识别可并行执行的任务层

### 13.2 P4-3: 工作流实例执行
- 实例化工作流
- 按拓扑顺序执行任务

### 13.3 可视化编排器（前端）
- 拖拽式DAG编辑器
- 实时预览依赖关系

---

## 14. 总结

工作流定义与解析是DAG工作流引擎的基础，核心价值：

✅ **标准化**：统一的JSON格式表达任务依赖  
✅ **安全性**：循环依赖检测确保DAG合法性  
✅ **可扩展**：支持多种任务类型和资源需求  
✅ **易维护**：版本控制，向后兼容  

**关键设计决策**：
- 使用JSON存储DAG定义（灵活、可读）
- DFS + 三色标记检测循环依赖（O(V+E)）
- 版本控制支持工作流演进
- 缓存解析后的DAG（性能优化）

这个设计为后续的拓扑排序和并行执行奠定了坚实基础。🚀
