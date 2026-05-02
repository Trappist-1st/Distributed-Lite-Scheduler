# P4: DAG工作流引擎设计稿

## 1. 文档目标

本文档详细设计DAG工作流引擎，在单任务调度基础上支持复杂的任务依赖关系，解决以下问题：

- 如何描述和存储任务间的依赖关系
- 如何保证任务按正确的顺序执行
- 如何最大化并行度，缩短工作流总执行时间
- 如何处理工作流中的任务失败
- 如何支持条件分支和动态路由

**核心价值**：从单任务调度升级到复杂工作流编排，支持数据管道、ETL、机器学习训练等场景。

---

## 2. DAG工作流原理

### 2.1 为什么需要工作流

**场景1：数据处理管道**
```
原始数据提取(Task A) 
    → 数据清洗(Task B) 
    → 特征工程(Task C) 
    → 模型训练(Task D)
```

**场景2：并行数据处理**
```
数据分片(Task A)
    ├→ 分片1处理(Task B1)
    ├→ 分片2处理(Task B2)
    └→ 分片3处理(Task B3)
        → 结果合并(Task C)
```

**场景3：复杂业务流程**
```
订单创建(Task A)
    ├→ 库存扣减(Task B)
    └→ 支付处理(Task C)
        → 发货通知(Task D)
```

### 2.2 DAG基本概念

#### 什么是DAG
```
DAG = Directed Acyclic Graph（有向无环图）

有向：任务A → 任务B，表示B依赖A
无环：不能出现 A → B → C → A 的循环依赖
```

#### DAG的表示方式

**邻接表表示**：
```java
Map<String, Set<String>> graph = new HashMap<>();
graph.put("A", Set.of("B", "C"));  // A的后继是B和C
graph.put("B", Set.of("D"));        // B的后继是D
graph.put("C", Set.of("D"));        // C的后继是D
graph.put("D", Set.of());           // D没有后继
```

**入度表表示**：
```java
Map<String, Integer> inDegree = new HashMap<>();
inDegree.put("A", 0);  // A没有前驱
inDegree.put("B", 1);  // B有1个前驱(A)
inDegree.put("C", 1);  // C有1个前驱(A)
inDegree.put("D", 2);  // D有2个前驱(B和C)
```

#### 拓扑排序
```
目标：找到一个任务执行顺序，使得每个任务的所有依赖都已完成

示例：
  A
 / \
B   C
 \ /
  D

拓扑排序结果：
- 第0层：[A]（无依赖）
- 第1层：[B, C]（可并行）
- 第2层：[D]（依赖B和C）
```

---

## 3. 工作流执行算法

### 3.1 Kahn拓扑排序算法

**核心思想**：
1. 找到所有入度为0的节点（无依赖）
2. 将这些节点加入执行队列
3. 执行完成后，将其后继节点的入度-1
4. 重复直到所有节点执行完成

**算法实现**：
```java
public List<List<String>> topologicalSort(
        Map<String, Set<String>> graph,
        Map<String, Integer> inDegree) {
    
    List<List<String>> layers = new ArrayList<>();
    Queue<String> queue = new LinkedList<>();
    Map<String, Integer> currentInDegree = new HashMap<>(inDegree);
    
    // 1. 找到所有入度为0的节点
    for (Map.Entry<String, Integer> entry : currentInDegree.entrySet()) {
        if (entry.getValue() == 0) {
            queue.offer(entry.getKey());
        }
    }
    
    // 2. 分层处理（同一层可并行执行）
    while (!queue.isEmpty()) {
        int layerSize = queue.size();
        List<String> currentLayer = new ArrayList<>();
        
        // 处理当前层的所有节点
        for (int i = 0; i < layerSize; i++) {
            String node = queue.poll();
            currentLayer.add(node);
            
            // 更新后继节点的入度
            Set<String> successors = graph.getOrDefault(node, Set.of());
            for (String successor : successors) {
                int newInDegree = currentInDegree.get(successor) - 1;
                currentInDegree.put(successor, newInDegree);
                
                // 入度变为0，加入队列
                if (newInDegree == 0) {
                    queue.offer(successor);
                }
            }
        }
        
        layers.add(currentLayer);
    }
    
    // 3. 检查是否存在环（未处理的节点说明有环）
    if (layers.stream().mapToInt(List::size).sum() != graph.size()) {
        throw new IllegalArgumentException("DAG包含环，无法执行");
    }
    
    return layers;
}
```

**算法复杂度**：
- 时间复杂度：O(V + E)，V是任务数，E是依赖关系数
- 空间复杂度：O(V)

### 3.2 循环依赖检测

**方法1：拓扑排序检测**
```java
// 如果排序后的节点数 < 总节点数，说明有环
if (sortedNodes.size() < totalNodes) {
    throw new CycleDetectedException();
}
```

**方法2：DFS检测（更详细）**
```java
public boolean hasCycle(Map<String, Set<String>> graph) {
    Set<String> visited = new HashSet<>();
    Set<String> recursionStack = new HashSet<>();
    
    for (String node : graph.keySet()) {
        if (hasCycleDFS(node, graph, visited, recursionStack)) {
            return true;
        }
    }
    
    return false;
}

private boolean hasCycleDFS(
        String node,
        Map<String, Set<String>> graph,
        Set<String> visited,
        Set<String> recursionStack) {
    
    visited.add(node);
    recursionStack.add(node);
    
    for (String neighbor : graph.getOrDefault(node, Set.of())) {
        if (!visited.contains(neighbor)) {
            if (hasCycleDFS(neighbor, graph, visited, recursionStack)) {
                return true;
            }
        } else if (recursionStack.contains(neighbor)) {
            // 在递归栈中发现已访问节点，说明有环
            return true;
        }
    }
    
    recursionStack.remove(node);
    return false;
}
```

### 3.3 并行执行策略

**策略1：按层并行执行（推荐）**
```java
for (List<String> layer : layers) {
    // 同一层的任务并行执行
    List<CompletableFuture<Void>> futures = layer.stream()
        .map(taskName -> CompletableFuture.runAsync(() -> {
            executeTask(taskName);
        }, executorService))
        .collect(Collectors.toList());
    
    // 等待当前层所有任务完成
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

**策略2：事件驱动执行（高级）**
```java
// 任务完成时触发事件
@EventListener
public void onTaskCompleted(TaskCompletedEvent event) {
    String taskName = event.getTaskName();
    
    // 找到所有依赖该任务的后继任务
    Set<String> successors = workflowGraph.get(taskName);
    
    for (String successor : successors) {
        // 检查后继任务的所有前驱是否都已完成
        if (allPredecessorsCompleted(successor)) {
            submitTask(successor);
        }
    }
}
```

---

## 4. 核心实现

### 4.1 数据模型设计

#### workflow表（工作流定义）
```sql
CREATE TABLE workflow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL COMMENT '工作流名称',
    description TEXT COMMENT '描述',
    project_id BIGINT NOT NULL COMMENT '所属项目',
    dag_json TEXT NOT NULL COMMENT 'DAG定义(JSON)',
    status VARCHAR(50) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/ARCHIVED',
    version INT DEFAULT 1 COMMENT '版本号',
    created_by BIGINT COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_project (project_id),
    INDEX idx_name (name)
) COMMENT '工作流定义表';
```

#### workflow_instance表（工作流实例）
```sql
CREATE TABLE workflow_instance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL COMMENT '工作流ID',
    workflow_version INT COMMENT '工作流版本快照',
    status VARCHAR(50) DEFAULT 'PENDING' COMMENT '状态',
    submit_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    start_time TIMESTAMP COMMENT '开始时间',
    end_time TIMESTAMP COMMENT '结束时间',
    context_json TEXT COMMENT '执行上下文(参数、变量等)',
    error_message TEXT COMMENT '错误信息',
    
    INDEX idx_workflow_status (workflow_id, status),
    INDEX idx_submit_time (submit_time)
) COMMENT '工作流实例表';

-- 状态枚举
-- PENDING: 等待执行
-- RUNNING: 执行中
-- SUCCESS: 全部成功
-- FAILED: 存在失败任务
-- PARTIAL_SUCCESS: 部分成功（失败继续模式）
-- CANCELLED: 已取消
```

#### task_dependency表（任务依赖关系）
```sql
CREATE TABLE task_dependency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL COMMENT '工作流ID',
    from_task_name VARCHAR(255) NOT NULL COMMENT '前驱任务名',
    to_task_name VARCHAR(255) NOT NULL COMMENT '后继任务名',
    condition_expr VARCHAR(1000) COMMENT '条件表达式(可选)',
    
    UNIQUE KEY uk_workflow_dep (workflow_id, from_task_name, to_task_name),
    INDEX idx_workflow (workflow_id)
) COMMENT '任务依赖关系表';
```

#### workflow_task_instance表（工作流中的任务实例）
```sql
CREATE TABLE workflow_task_instance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_instance_id BIGINT NOT NULL COMMENT '工作流实例ID',
    task_name VARCHAR(255) NOT NULL COMMENT '任务名称',
    task_instance_id BIGINT COMMENT '关联的task_instance.id',
    status VARCHAR(50) DEFAULT 'PENDING' COMMENT '状态',
    layer_index INT COMMENT '所在层级',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    submit_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    
    INDEX idx_workflow_instance (workflow_instance_id),
    INDEX idx_status (status),
    INDEX idx_layer (workflow_instance_id, layer_index)
) COMMENT '工作流任务实例表';
```

### 4.2 DAG JSON格式

```json
{
  "name": "data_pipeline",
  "version": "1.0",
  "tasks": {
    "extract": {
      "type": "shell",
      "script": "python extract.py",
      "resource": {"cpu": 2, "memory": 4096}
    },
    "clean": {
      "type": "python",
      "script": "clean.py",
      "resource": {"cpu": 1, "memory": 2048}
    },
    "transform": {
      "type": "python",
      "script": "transform.py",
      "resource": {"cpu": 2, "memory": 4096}
    },
    "load": {
      "type": "shell",
      "script": "python load.py",
      "resource": {"cpu": 1, "memory": 2048}
    }
  },
  "dependencies": [
    {"from": "extract", "to": "clean"},
    {"from": "clean", "to": "transform"},
    {"from": "transform", "to": "load"}
  ],
  "config": {
    "failurePolicy": "STOP_ON_FAILURE",
    "maxRetries": 3,
    "timeout": 3600
  }
}
```

### 4.3 工作流服务实现

```java
@Service
@Slf4j
public class WorkflowService {
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    @Autowired
    private WorkflowInstanceMapper workflowInstanceMapper;
    
    @Autowired
    private TaskDependencyMapper taskDependencyMapper;
    
    @Autowired
    private WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    
    @Autowired
    private TaskSubmitService taskSubmitService;
    
    /**
     * 创建工作流
     */
    @Transactional
    public Long createWorkflow(CreateWorkflowRequest request) {
        // 1. 解析DAG JSON
        WorkflowDAG dag = parseDAG(request.getDagJson());
        
        // 2. 验证DAG（循环检测）
        validateDAG(dag);
        
        // 3. 保存工作流定义
        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setProjectId(request.getProjectId());
        workflow.setDagJson(request.getDagJson());
        workflow.setCreatedBy(getCurrentUserId());
        workflowMapper.insert(workflow);
        
        // 4. 保存依赖关系
        for (Dependency dep : dag.getDependencies()) {
            TaskDependency taskDep = new TaskDependency();
            taskDep.setWorkflowId(workflow.getId());
            taskDep.setFromTaskName(dep.getFrom());
            taskDep.setToTaskName(dep.getTo());
            taskDep.setConditionExpr(dep.getCondition());
            taskDependencyMapper.insert(taskDep);
        }
        
        log.info("创建工作流成功 workflowId={} taskCount={}", 
            workflow.getId(), dag.getTasks().size());
        
        return workflow.getId();
    }
    
    /**
     * 提交工作流执行
     */
    @Transactional
    public Long submitWorkflow(Long workflowId, Map<String, Object> context) {
        // 1. 查询工作流定义
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new NotFoundException("工作流不存在");
        }
        
        // 2. 创建工作流实例
        WorkflowInstance instance = new WorkflowInstance();
        instance.setWorkflowId(workflowId);
        instance.setWorkflowVersion(workflow.getVersion());
        instance.setStatus(WorkflowStatus.PENDING);
        instance.setContextJson(JSON.toJSONString(context));
        workflowInstanceMapper.insert(instance);
        
        // 3. 解析DAG并执行拓扑排序
        WorkflowDAG dag = parseDAG(workflow.getDagJson());
        List<List<String>> layers = topologicalSort(dag);
        
        // 4. 创建所有任务实例（状态为PENDING）
        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            for (String taskName : layers.get(layerIndex)) {
                WorkflowTaskInstance taskInstance = new WorkflowTaskInstance();
                taskInstance.setWorkflowInstanceId(instance.getId());
                taskInstance.setTaskName(taskName);
                taskInstance.setStatus(TaskStatus.PENDING);
                taskInstance.setLayerIndex(layerIndex);
                workflowTaskInstanceMapper.insert(taskInstance);
            }
        }
        
        // 5. 异步执行工作流
        asyncExecuteWorkflow(instance.getId());
        
        log.info("提交工作流执行 workflowId={} instanceId={} layers={}", 
            workflowId, instance.getId(), layers.size());
        
        return instance.getId();
    }
    
    /**
     * 异步执行工作流
     */
    @Async
    public void asyncExecuteWorkflow(Long workflowInstanceId) {
        try {
            executeWorkflow(workflowInstanceId);
        } catch (Exception e) {
            log.error("工作流执行异常 instanceId={}", workflowInstanceId, e);
            updateWorkflowStatus(workflowInstanceId, WorkflowStatus.FAILED, 
                e.getMessage());
        }
    }
    
    /**
     * 执行工作流（按层并行）
     */
    private void executeWorkflow(Long workflowInstanceId) {
        // 1. 更新工作流状态为RUNNING
        updateWorkflowStatus(workflowInstanceId, WorkflowStatus.RUNNING, null);
        
        // 2. 查询所有任务实例（按层级分组）
        List<WorkflowTaskInstance> allTasks = 
            workflowTaskInstanceMapper.selectByWorkflowInstance(workflowInstanceId);
        
        Map<Integer, List<WorkflowTaskInstance>> layerMap = allTasks.stream()
            .collect(Collectors.groupingBy(WorkflowTaskInstance::getLayerIndex));
        
        // 3. 按层执行
        int maxLayer = layerMap.keySet().stream().max(Integer::compareTo).orElse(0);
        
        for (int layer = 0; layer <= maxLayer; layer++) {
            List<WorkflowTaskInstance> layerTasks = layerMap.get(layer);
            
            log.info("执行工作流第{}层 instanceId={} taskCount={}", 
                layer, workflowInstanceId, layerTasks.size());
            
            // 4. 并行执行当前层的所有任务
            List<CompletableFuture<Void>> futures = layerTasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> {
                    executeWorkflowTask(workflowInstanceId, task);
                }, workflowExecutor))
                .collect(Collectors.toList());
            
            // 5. 等待当前层所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 6. 检查是否有任务失败
            boolean hasFailure = layerTasks.stream()
                .anyMatch(t -> TaskStatus.FAILED.equals(t.getStatus()));
            
            if (hasFailure) {
                // 根据失败策略处理
                WorkflowInstance instance = 
                    workflowInstanceMapper.selectById(workflowInstanceId);
                WorkflowDAG dag = parseDAG(
                    workflowMapper.selectById(instance.getWorkflowId()).getDagJson()
                );
                
                if ("STOP_ON_FAILURE".equals(dag.getConfig().getFailurePolicy())) {
                    log.warn("工作流任务失败，终止执行 instanceId={}", 
                        workflowInstanceId);
                    updateWorkflowStatus(workflowInstanceId, 
                        WorkflowStatus.FAILED, "任务失败");
                    return;
                }
            }
        }
        
        // 7. 所有任务执行完成，更新工作流状态
        boolean allSuccess = allTasks.stream()
            .allMatch(t -> TaskStatus.SUCCESS.equals(t.getStatus()));
        
        if (allSuccess) {
            updateWorkflowStatus(workflowInstanceId, WorkflowStatus.SUCCESS, null);
        } else {
            updateWorkflowStatus(workflowInstanceId, 
                WorkflowStatus.PARTIAL_SUCCESS, "部分任务失败");
        }
        
        log.info("工作流执行完成 instanceId={} status={}", 
            workflowInstanceId, allSuccess ? "SUCCESS" : "PARTIAL_SUCCESS");
    }
    
    /**
     * 执行单个工作流任务
     */
    private void executeWorkflowTask(
            Long workflowInstanceId, 
            WorkflowTaskInstance workflowTask) {
        
        try {
            // 1. 查询工作流定义中的任务配置
            WorkflowInstance instance = 
                workflowInstanceMapper.selectById(workflowInstanceId);
            Workflow workflow = 
                workflowMapper.selectById(instance.getWorkflowId());
            WorkflowDAG dag = parseDAG(workflow.getDagJson());
            TaskDefinition taskDef = dag.getTasks().get(workflowTask.getTaskName());
            
            // 2. 构造任务提交请求
            TaskSubmitRequest submitRequest = new TaskSubmitRequest();
            submitRequest.setTaskName(workflowTask.getTaskName());
            submitRequest.setTaskType(taskDef.getType());
            submitRequest.setScript(taskDef.getScript());
            submitRequest.setResourceRequirement(taskDef.getResource());
            
            // 3. 提交任务到调度器
            TaskSubmitResponse response = taskSubmitService.submitTask(submitRequest);
            
            // 4. 更新workflowTaskInstance
            workflowTask.setTaskInstanceId(response.getTaskInstanceId());
            workflowTask.setStatus(TaskStatus.RUNNING);
            workflowTask.setStartTime(LocalDateTime.now());
            workflowTaskInstanceMapper.updateById(workflowTask);
            
            // 5. 等待任务完成（轮询或异步通知）
            waitForTaskCompletion(workflowTask);
            
        } catch (Exception e) {
            log.error("工作流任务执行失败 workflowTaskId={}", 
                workflowTask.getId(), e);
            workflowTask.setStatus(TaskStatus.FAILED);
            workflowTask.setEndTime(LocalDateTime.now());
            workflowTaskInstanceMapper.updateById(workflowTask);
        }
    }
    
    /**
     * 等待任务完成
     */
    private void waitForTaskCompletion(WorkflowTaskInstance workflowTask) {
        // 轮询任务状态
        while (true) {
            TaskInstance taskInstance = 
                taskInstanceMapper.selectById(workflowTask.getTaskInstanceId());
            
            if (TaskStatus.SUCCESS.equals(taskInstance.getStatus())) {
                workflowTask.setStatus(TaskStatus.SUCCESS);
                workflowTask.setEndTime(LocalDateTime.now());
                workflowTaskInstanceMapper.updateById(workflowTask);
                break;
            } else if (TaskStatus.FAILED.equals(taskInstance.getStatus())) {
                workflowTask.setStatus(TaskStatus.FAILED);
                workflowTask.setEndTime(LocalDateTime.now());
                workflowTaskInstanceMapper.updateById(workflowTask);
                break;
            }
            
            // 等待5秒后重试
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * 验证DAG（循环检测）
     */
    private void validateDAG(WorkflowDAG dag) {
        Map<String, Set<String>> graph = buildGraph(dag);
        
        if (hasCycle(graph)) {
            throw new IllegalArgumentException("DAG包含循环依赖，无法执行");
        }
        
        // 验证所有任务都被引用
        Set<String> referencedTasks = new HashSet<>();
        for (Dependency dep : dag.getDependencies()) {
            referencedTasks.add(dep.getFrom());
            referencedTasks.add(dep.getTo());
        }
        
        for (String taskName : dag.getTasks().keySet()) {
            if (!referencedTasks.contains(taskName) && dag.getDependencies().size() > 0) {
                log.warn("任务未被依赖关系引用 taskName={}", taskName);
            }
        }
    }
}
```

### 4.4 DAG解析和拓扑排序工具类

```java
@Component
public class DAGUtils {
    
    /**
     * 解析DAG JSON
     */
    public WorkflowDAG parseDAG(String dagJson) {
        return JSON.parseObject(dagJson, WorkflowDAG.class);
    }
    
    /**
     * 拓扑排序（分层）
     */
    public List<List<String>> topologicalSort(WorkflowDAG dag) {
        Map<String, Set<String>> graph = buildGraph(dag);
        Map<String, Integer> inDegree = buildInDegree(dag);
        
        List<List<String>> layers = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> currentInDegree = new HashMap<>(inDegree);
        
        // 找到所有入度为0的节点
        for (Map.Entry<String, Integer> entry : currentInDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        // 分层处理
        while (!queue.isEmpty()) {
            int layerSize = queue.size();
            List<String> currentLayer = new ArrayList<>();
            
            for (int i = 0; i < layerSize; i++) {
                String node = queue.poll();
                currentLayer.add(node);
                
                // 更新后继节点的入度
                Set<String> successors = graph.getOrDefault(node, Set.of());
                for (String successor : successors) {
                    int newInDegree = currentInDegree.get(successor) - 1;
                    currentInDegree.put(successor, newInDegree);
                    
                    if (newInDegree == 0) {
                        queue.offer(successor);
                    }
                }
            }
            
            layers.add(currentLayer);
        }
        
        // 检查是否有环
        int totalProcessed = layers.stream().mapToInt(List::size).sum();
        if (totalProcessed != dag.getTasks().size()) {
            throw new IllegalArgumentException("DAG包含环，无法执行");
        }
        
        return layers;
    }
    
    /**
     * 构建邻接表
     */
    private Map<String, Set<String>> buildGraph(WorkflowDAG dag) {
        Map<String, Set<String>> graph = new HashMap<>();
        
        // 初始化所有节点
        for (String taskName : dag.getTasks().keySet()) {
            graph.put(taskName, new HashSet<>());
        }
        
        // 添加边
        for (Dependency dep : dag.getDependencies()) {
            graph.get(dep.getFrom()).add(dep.getTo());
        }
        
        return graph;
    }
    
    /**
     * 构建入度表
     */
    private Map<String, Integer> buildInDegree(WorkflowDAG dag) {
        Map<String, Integer> inDegree = new HashMap<>();
        
        // 初始化所有节点入度为0
        for (String taskName : dag.getTasks().keySet()) {
            inDegree.put(taskName, 0);
        }
        
        // 计算入度
        for (Dependency dep : dag.getDependencies()) {
            inDegree.put(dep.getTo(), inDegree.get(dep.getTo()) + 1);
        }
        
        return inDegree;
    }
    
    /**
     * 检测环（DFS）
     */
    public boolean hasCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String node : graph.keySet()) {
            if (hasCycleDFS(node, graph, visited, recursionStack)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean hasCycleDFS(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> recursionStack) {
        
        visited.add(node);
        recursionStack.add(node);
        
        for (String neighbor : graph.getOrDefault(node, Set.of())) {
            if (!visited.contains(neighbor)) {
                if (hasCycleDFS(neighbor, graph, visited, recursionStack)) {
                    return true;
                }
            } else if (recursionStack.contains(neighbor)) {
                return true;
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
}
```

---

## 5. API设计

### 5.1 工作流管理API

#### 创建工作流
```http
POST /api/workflow
Content-Type: application/json

{
  "name": "数据处理管道",
  "description": "ETL工作流",
  "projectId": 1,
  "dagJson": "{...}"
}

Response:
{
  "code": 0,
  "data": {
    "workflowId": 123
  }
}
```

#### 提交工作流执行
```http
POST /api/workflow/{workflowId}/submit
Content-Type: application/json

{
  "context": {
    "input_path": "/data/input",
    "output_path": "/data/output",
    "date": "2026-05-01"
  }
}

Response:
{
  "code": 0,
  "data": {
    "workflowInstanceId": 456
  }
}
```

#### 查询工作流实例状态
```http
GET /api/workflow/instance/{instanceId}

Response:
{
  "code": 0,
  "data": {
    "instanceId": 456,
    "workflowId": 123,
    "status": "RUNNING",
    "submitTime": "2026-05-01T10:00:00",
    "startTime": "2026-05-01T10:00:05",
    "tasks": [
      {
        "taskName": "extract",
        "status": "SUCCESS",
        "layerIndex": 0,
        "duration": 120
      },
      {
        "taskName": "clean",
        "status": "RUNNING",
        "layerIndex": 1,
        "duration": null
      }
    ]
  }
}
```

#### 取消工作流执行
```http
POST /api/workflow/instance/{instanceId}/cancel

Response:
{
  "code": 0,
  "message": "工作流已取消"
}
```

### 5.2 工作流可视化API

#### 获取工作流DAG
```http
GET /api/workflow/{workflowId}/dag

Response:
{
  "code": 0,
  "data": {
    "nodes": [
      {"id": "A", "label": "提取数据", "type": "shell"},
      {"id": "B", "label": "清洗数据", "type": "python"},
      {"id": "C", "label": "转换数据", "type": "python"},
      {"id": "D", "label": "加载数据", "type": "shell"}
    ],
    "edges": [
      {"from": "A", "to": "B"},
      {"from": "B", "to": "C"},
      {"from": "C", "to": "D"}
    ]
  }
}
```

---

## 6. 失败处理策略

### 6.1 失败策略

**策略1：STOP_ON_FAILURE（默认）**
```
任务失败 → 立即停止工作流 → 标记为FAILED
```

**策略2：CONTINUE_ON_FAILURE**
```
任务失败 → 继续执行其他任务 → 标记为PARTIAL_SUCCESS
```

**策略3：RETRY_ON_FAILURE**
```
任务失败 → 重试N次 → 仍失败则根据策略1或2处理
```

### 6.2 重试机制

```java
@Configuration
public class WorkflowRetryConfig {
    
    @Bean
    public RetryTemplate workflowRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        
        // 重试策略：最多3次
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        
        // 退避策略：指数退避
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000);
        
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        
        return retryTemplate;
    }
}
```

### 6.3 部分失败处理

```java
public void handlePartialFailure(Long workflowInstanceId) {
    // 查询失败的任务
    List<WorkflowTaskInstance> failedTasks = 
        workflowTaskInstanceMapper.selectByWorkflowInstanceAndStatus(
            workflowInstanceId, TaskStatus.FAILED
        );
    
    // 查询成功的任务
    List<WorkflowTaskInstance> successTasks = 
        workflowTaskInstanceMapper.selectByWorkflowInstanceAndStatus(
            workflowInstanceId, TaskStatus.SUCCESS
        );
    
    log.warn("工作流部分失败 instanceId={} success={} failed={}", 
        workflowInstanceId, successTasks.size(), failedTasks.size());
    
    // 支持重新执行失败的任务
    for (WorkflowTaskInstance task : failedTasks) {
        log.info("失败任务 taskName={} error={}", 
            task.getTaskName(), task.getErrorMessage());
    }
}
```

---

## 7. 条件分支支持（进阶）

### 7.1 条件表达式

使用SpEL（Spring Expression Language）支持条件分支：

```json
{
  "tasks": {
    "check_data": {
      "type": "python",
      "script": "check.py"
    },
    "process_valid": {
      "type": "python",
      "script": "process.py"
    },
    "process_invalid": {
      "type": "python",
      "script": "handle_error.py"
    }
  },
  "dependencies": [
    {
      "from": "check_data",
      "to": "process_valid",
      "condition": "${check_data.result == 'valid'}"
    },
    {
      "from": "check_data",
      "to": "process_invalid",
      "condition": "${check_data.result == 'invalid'}"
    }
  ]
}
```

### 7.2 条件评估器

```java
@Component
public class ConditionEvaluator {
    
    private final SpelExpressionParser parser = new SpelExpressionParser();
    
    /**
     * 评估条件表达式
     */
    public boolean evaluate(String conditionExpr, Map<String, Object> context) {
        if (StringUtils.isEmpty(conditionExpr)) {
            return true; // 无条件，默认执行
        }
        
        try {
            Expression expression = parser.parseExpression(conditionExpr);
            StandardEvaluationContext evalContext = new StandardEvaluationContext(context);
            
            Boolean result = expression.getValue(evalContext, Boolean.class);
            return result != null && result;
            
        } catch (Exception e) {
            log.error("条件表达式评估失败 expr={}", conditionExpr, e);
            return false;
        }
    }
}
```

### 7.3 动态路由

```java
private void executeWorkflowWithConditions(Long workflowInstanceId) {
    Map<String, Object> context = new HashMap<>();
    
    // 按层执行
    for (int layer = 0; layer <= maxLayer; layer++) {
        List<WorkflowTaskInstance> layerTasks = getLayerTasks(layer);
        
        for (WorkflowTaskInstance task : layerTasks) {
            // 检查前驱任务的条件
            if (shouldExecuteTask(task, context)) {
                executeTask(task);
                
                // 将任务结果加入上下文
                context.put(task.getTaskName(), getTaskResult(task));
            } else {
                log.info("跳过任务执行（条件不满足） taskName={}", 
                    task.getTaskName());
                task.setStatus(TaskStatus.SKIPPED);
                workflowTaskInstanceMapper.updateById(task);
            }
        }
    }
}

private boolean shouldExecuteTask(
        WorkflowTaskInstance task, 
        Map<String, Object> context) {
    
    // 查询该任务的所有前驱依赖
    List<TaskDependency> dependencies = 
        taskDependencyMapper.selectDependenciesTo(
            task.getWorkflowId(), task.getTaskName()
        );
    
    // 所有条件都满足才执行
    for (TaskDependency dep : dependencies) {
        if (StringUtils.isNotEmpty(dep.getConditionExpr())) {
            if (!conditionEvaluator.evaluate(dep.getConditionExpr(), context)) {
                return false;
            }
        }
    }
    
    return true;
}
```

---

## 8. 监控与分析

### 8.1 工作流执行统计

```sql
-- 工作流执行成功率
SELECT 
    w.name,
    COUNT(*) as total_runs,
    SUM(CASE WHEN wi.status='SUCCESS' THEN 1 ELSE 0 END) as success_count,
    AVG(TIMESTAMPDIFF(SECOND, wi.start_time, wi.end_time)) as avg_duration_sec
FROM workflow_instance wi
JOIN workflow w ON wi.workflow_id = w.id
WHERE wi.submit_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY w.id, w.name
ORDER BY total_runs DESC;
```

### 8.2 任务执行热力图

```sql
-- 各任务的执行频率和失败率
SELECT 
    wti.task_name,
    COUNT(*) as execution_count,
    SUM(CASE WHEN wti.status='FAILED' THEN 1 ELSE 0 END) as failure_count,
    AVG(TIMESTAMPDIFF(SECOND, wti.start_time, wti.end_time)) as avg_duration_sec
FROM workflow_task_instance wti
WHERE wti.submit_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY wti.task_name
ORDER BY failure_count DESC;
```

### 8.3 工作流实时监控

```java
@RestController
@RequestMapping("/api/workflow/monitor")
public class WorkflowMonitorController {
    
    /**
     * 获取正在执行的工作流
     */
    @GetMapping("/running")
    public Result<List<WorkflowMonitorVO>> getRunningWorkflows() {
        List<WorkflowInstance> runningInstances = 
            workflowInstanceMapper.selectByStatus(WorkflowStatus.RUNNING);
        
        return Result.success(runningInstances.stream()
            .map(this::buildMonitorVO)
            .collect(Collectors.toList()));
    }
    
    private WorkflowMonitorVO buildMonitorVO(WorkflowInstance instance) {
        // 查询任务进度
        List<WorkflowTaskInstance> allTasks = 
            workflowTaskInstanceMapper.selectByWorkflowInstance(instance.getId());
        
        long totalTasks = allTasks.size();
        long completedTasks = allTasks.stream()
            .filter(t -> TaskStatus.SUCCESS.equals(t.getStatus()) || 
                         TaskStatus.FAILED.equals(t.getStatus()))
            .count();
        
        WorkflowMonitorVO vo = new WorkflowMonitorVO();
        vo.setInstanceId(instance.getId());
        vo.setWorkflowName(getWorkflowName(instance.getWorkflowId()));
        vo.setStatus(instance.getStatus());
        vo.setProgress((int) (completedTasks * 100 / totalTasks));
        vo.setElapsedSeconds(
            ChronoUnit.SECONDS.between(instance.getStartTime(), LocalDateTime.now())
        );
        
        return vo;
    }
}
```

### 8.4 Grafana面板

```
┌─────────────────────────────────────────────────────┐
│  工作流执行监控                                       │
├─────────────────────────────────────────────────────┤
│  正在执行: 5个                                       │
│    - 数据处理管道 (进度: 60%, 用时: 120s)            │
│    - 模型训练流程 (进度: 30%, 用时: 300s)            │
│                                                     │
│  今日执行统计:                                       │
│    总数: 120                                        │
│    成功: 95 (79.2%)                                 │
│    失败: 15 (12.5%)                                 │
│    部分成功: 10 (8.3%)                              │
│                                                     │
│  平均执行时长: 180s                                  │
│  最慢工作流: ML训练管道 (avg: 600s)                  │
└─────────────────────────────────────────────────────┘
```

---

## 9. 性能优化

### 9.1 DAG解析缓存

```java
@Service
public class WorkflowCacheService {
    
    @Cacheable(value = "workflow:dag", key = "#workflowId")
    public WorkflowDAG getWorkflowDAG(Long workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        return dagUtils.parseDAG(workflow.getDagJson());
    }
    
    @CacheEvict(value = "workflow:dag", key = "#workflowId")
    public void evictCache(Long workflowId) {
        // 工作流更新时清除缓存
    }
}
```

### 9.2 并行度控制

```java
@Configuration
public class WorkflowExecutorConfig {
    
    @Bean("workflowExecutor")
    public ThreadPoolExecutor workflowExecutor() {
        return new ThreadPoolExecutor(
            10,  // 核心线程数
            50,  // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactoryBuilder()
                .setNameFormat("workflow-exec-%d")
                .build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

### 9.3 大规模DAG优化

**问题**：当工作流包含1000+任务时，拓扑排序和状态轮询成为瓶颈。

**优化方案**：

```java
// 1. 使用Redis存储任务状态，避免频繁查询数据库
public class WorkflowStateManager {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    public void updateTaskStatus(Long instanceId, String taskName, String status) {
        String key = "workflow:instance:" + instanceId + ":tasks";
        redisTemplate.opsForHash().put(key, taskName, status);
    }
    
    public Map<String, String> getAllTaskStatus(Long instanceId) {
        String key = "workflow:instance:" + instanceId + ":tasks";
        return redisTemplate.<String, String>opsForHash().entries(key);
    }
}

// 2. 使用事件驱动替代轮询
@Service
public class TaskCompletionListener {
    
    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        // 检查工作流中依赖该任务的后继任务
        checkAndTriggerSuccessors(event.getWorkflowInstanceId(), 
            event.getTaskName());
    }
}

// 3. 分批提交任务（避免一次性创建大量任务实例）
public void submitLargeWorkflow(Long workflowId) {
    List<List<String>> layers = topologicalSort(dag);
    
    // 只创建第一层的任务实例
    createTaskInstances(workflowInstanceId, layers.get(0), 0);
    
    // 后续层在前一层完成后再创建
}
```

---

## 10. 测试方案

### 10.1 DAG解析测试

```java
@Test
public void testParseSimpleDAG() {
    String dagJson = """
        {
          "tasks": {"A": {}, "B": {}, "C": {}},
          "dependencies": [
            {"from": "A", "to": "B"},
            {"from": "B", "to": "C"}
          ]
        }
        """;
    
    WorkflowDAG dag = dagUtils.parseDAG(dagJson);
    
    assertEquals(3, dag.getTasks().size());
    assertEquals(2, dag.getDependencies().size());
}
```

### 10.2 循环检测测试

```java
@Test
public void testCycleDetection() {
    WorkflowDAG dag = new WorkflowDAG();
    // A → B → C → A (环)
    dag.addDependency("A", "B");
    dag.addDependency("B", "C");
    dag.addDependency("C", "A");
    
    assertThrows(IllegalArgumentException.class, () -> {
        workflowService.createWorkflow(dag);
    });
}
```

### 10.3 拓扑排序测试

```java
@Test
public void testTopologicalSort() {
    WorkflowDAG dag = buildDiamondDAG(); // A → B,C → D
    
    List<List<String>> layers = dagUtils.topologicalSort(dag);
    
    assertEquals(3, layers.size());
    assertEquals(List.of("A"), layers.get(0));
    assertTrue(layers.get(1).containsAll(List.of("B", "C")));
    assertEquals(List.of("D"), layers.get(2));
}
```

### 10.4 并行执行测试

```java
@Test
public void testParallelExecution() {
    // 创建工作流（B和C可并行执行）
    Long workflowId = createDiamondWorkflow();
    
    // 提交执行
    long start = System.currentTimeMillis();
    Long instanceId = workflowService.submitWorkflow(workflowId, Map.of());
    
    // 等待完成
    waitForWorkflowCompletion(instanceId);
    long elapsed = System.currentTimeMillis() - start;
    
    // 验证B和C确实并行执行（总耗时 < B耗时 + C耗时）
    assertTrue(elapsed < 150); // B=100ms, C=100ms, 并行应<150ms
}
```

### 10.5 失败处理测试

```java
@Test
public void testStopOnFailure() {
    // 创建工作流（B会失败）
    Long workflowId = createWorkflowWithFailingTask();
    Long instanceId = workflowService.submitWorkflow(workflowId, Map.of());
    
    waitForWorkflowCompletion(instanceId);
    
    // 验证工作流状态为FAILED
    WorkflowInstance instance = 
        workflowInstanceMapper.selectById(instanceId);
    assertEquals(WorkflowStatus.FAILED, instance.getStatus());
    
    // 验证后续任务未执行
    List<WorkflowTaskInstance> tasks = 
        workflowTaskInstanceMapper.selectByWorkflowInstance(instanceId);
    assertTrue(tasks.stream()
        .anyMatch(t -> TaskStatus.PENDING.equals(t.getStatus())));
}
```

---

## 11. 常见问题

### Q1: 如何处理大规模DAG（1000+任务）？

**答**：
1. 使用Redis缓存任务状态，减少数据库压力
2. 采用事件驱动替代轮询
3. 分批创建任务实例，而非一次性全部创建
4. 考虑将工作流拆分为多个子工作流

### Q2: 如何调试工作流执行？

**答**：
1. 查看工作流实例详情API，获取每个任务的状态
2. 查询workflow_task_instance表，分析任务执行时间
3. 使用Grafana可视化工作流执行过程
4. 支持单任务重新执行，便于调试

### Q3: 工作流执行过程中可以修改DAG吗？

**答**：
不可以。工作流实例会保存创建时的DAG快照（workflow_version），执行过程中使用快照，确保一致性。

### Q4: 如何实现定时工作流？

**答**：
结合Cron调度：

```java
@Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
public void scheduleDailyETL() {
    workflowService.submitWorkflow(ETL_WORKFLOW_ID, Map.of(
        "date", LocalDate.now().toString()
    ));
}
```

### Q5: 支持工作流参数传递吗？

**答**：
支持。通过context传递参数：

```java
workflowService.submitWorkflow(workflowId, Map.of(
    "input_path", "/data/input",
    "date", "2026-05-01"
));
```

任务脚本中可以通过环境变量访问这些参数。

---

## 12. 与单任务调度对比

| 维度 | 单任务调度 | DAG工作流 |
|-----|-----------|----------|
| 任务关系 | 独立执行 | 支持依赖关系 |
| 并行度 | 资源允许即可并行 | 按依赖层级并行 |
| 失败处理 | 单任务重试 | 工作流级失败策略 |
| 状态管理 | 任务级状态 | 工作流+任务双层状态 |
| 复杂度 | 低 | 中高 |
| 适用场景 | 独立任务 | 数据管道、ETL、ML训练 |

---

## 13. 典型应用场景

### 13.1 数据ETL管道

```
提取(Extract) → 清洗(Clean) → 转换(Transform) → 加载(Load)
```

### 13.2 机器学习训练

```
数据预处理
    ├→ 特征工程
    └→ 数据划分(Train/Val/Test)
        → 模型训练
        → 模型评估
        → 模型部署
```

### 13.3 多租户报表生成

```
数据汇总
    ├→ 租户A报表生成
    ├→ 租户B报表生成
    └→ 租户C报表生成
        → 报表归档
```

---

## 14. 后续优化方向

### 14.1 P4-5: 子工作流支持

支持工作流嵌套：

```json
{
  "tasks": {
    "main_task": {"type": "shell"},
    "sub_workflow": {
      "type": "workflow",
      "workflowId": 456
    }
  }
}
```

### 14.2 P4-6: 动态任务生成

根据运行时结果动态生成任务：

```java
// 根据数据分片数量动态创建处理任务
int shardCount = getShardCount();
for (int i = 0; i < shardCount; i++) {
    addDynamicTask("process_shard_" + i);
}
```

### 14.3 P4-7: 工作流模板市场

提供常用工作流模板：
- ETL管道模板
- 数据备份模板
- 模型训练模板

### 14.4 P4-8: 可视化DAG编辑器

Web界面拖拽式创建工作流：
- 拖拽任务节点
- 连接依赖关系
- 配置任务参数
- 实时预览DAG

---

## 15. 总结

DAG工作流引擎是调度系统的**高级功能**，核心价值：

✅ **任务编排**：支持复杂的任务依赖关系  
✅ **并行优化**：自动识别可并行任务，提高效率  
✅ **失败容错**：灵活的失败处理策略  
✅ **条件分支**：支持动态路由和条件执行  

**关键设计决策**：
- Kahn拓扑排序算法（O(V+E)复杂度）
- 按层并行执行（最大化并行度）
- 工作流版本快照（确保执行一致性）
- 事件驱动 + 轮询混合模式（平衡实时性和性能）

**技术难点**：
- 循环依赖检测
- 大规模DAG性能优化
- 失败任务的状态传播
- 条件分支的上下文管理

这个设计能够满足 **数据管道、ETL、机器学习训练** 等典型场景，是调度器从任务级走向流程级的关键升级。

---

## 附录：参考资料

- Apache Airflow DAG设计
- Kubernetes Job/CronJob
- Argo Workflows
- Temporal Workflow Engine
- 拓扑排序算法（Kahn算法、DFS算法）
