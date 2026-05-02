# P4-4: 条件分支支持设计稿（进阶特性）

## 1. 文档目标

本文档详细设计工作流的条件分支功能，在基础DAG执行基础上增加动态路由能力，解决以下问题：

- 如何根据任务执行结果动态决定后续执行路径
- 如何表达和解析条件表达式
- 如何处理条件不满足的任务（跳过）
- 如何实现复杂的业务逻辑（if-else、switch等）

**核心价值**：让工作流具备智能决策能力，从"静态DAG"升级为"动态工作流"，是工作流引擎的"大脑升级"。

---

## 2. 条件分支核心概念

### 2.1 什么是条件分支

**定义**：根据前置任务的执行结果（状态、输出、变量等）动态决定是否执行后续任务。

**示例场景：数据质量检查工作流**
```
┌─────────────────────────────────────────────┐
│        数据质量检查工作流                     │
├─────────────────────────────────────────────┤
│                                             │
│    ┌──────────┐                            │
│    │ 数据验证  │                            │
│    └─────┬────┘                            │
│          │                                 │
│     ┌────▼─────┐                           │
│     │ 质量评分  │                           │
│     └─────┬────┘                           │
│           │                                │
│    ┌──────▼──────┐                         │
│    │  分数 >= 90? │                         │
│    └──┬──────┬───┘                         │
│       │ YES  │ NO                          │
│   ┌───▼──┐ ┌▼────────┐                    │
│   │ 直接 │ │ 人工审核 │                    │
│   │ 导入 │ │         │                    │
│   └──────┘ └─────────┘                    │
│                                             │
└─────────────────────────────────────────────┘
```

**传统DAG vs 条件分支**：
```
传统DAG：
  A → B → C → D
  所有任务都会执行

条件分支：
  A → B → [条件判断]
           ├─ 条件满足 → C
           └─ 条件不满足 → D
  只执行满足条件的分支
```

### 2.2 为什么需要条件分支

**场景1：数据质量检查**
```
数据验证通过 → 自动导入
数据验证失败 → 人工审核 + 告警
```

**场景2：模型训练**
```
模型精度 >= 0.95 → 自动部署
模型精度 < 0.95  → 重新训练 + 参数调优
```

**场景3：业务审批流程**
```
金额 <= 1000    → 自动审批
金额 > 1000     → 主管审批
金额 > 10000    → 总监审批
```

**场景4：A/B测试**
```
用户ID % 2 == 0 → 运行版本A
用户ID % 2 == 1 → 运行版本B
```

**价值**：
- ✅ **智能决策**：根据实际情况动态调整执行路径
- ✅ **资源节省**：跳过不必要的任务
- ✅ **业务灵活性**：满足复杂业务逻辑
- ✅ **可维护性**：逻辑清晰，易于理解

---

## 3. 条件表达式设计

### 3.1 表达式语法（SpEL）

**选择SpEL（Spring Expression Language）的原因**：
- ✅ Spring原生支持，无需额外依赖
- ✅ 语法丰富，支持复杂表达式
- ✅ 类型安全
- ✅ 易于扩展

**基础语法**：
```java
// 访问任务输出
${task_a.output.score}

// 比较运算
${task_a.output.score >= 90}

// 逻辑运算
${task_a.status == 'SUCCESS' and task_b.output.count > 100}

// 字符串操作
${task_a.output.result.contains('error')}

// 算术运算
${task_a.output.value * 2 + 10}

// 三元运算
${task_a.output.score >= 90 ? 'pass' : 'fail'}

// 访问上下文变量
${workflow.context.userId}
```

### 3.2 表达式上下文

**可用变量**：
```java
public class ConditionContext {
    // 任务执行结果
    Map<String, TaskResult> tasks;
    
    // 工作流上下文
    Map<String, Object> context;
    
    // 系统变量
    Map<String, Object> system;
}

public class TaskResult {
    String status;           // 任务状态: SUCCESS, FAILED
    int exitCode;            // 退出码
    Map<String, Object> output;  // 任务输出（JSON解析）
    long durationSeconds;    // 执行时长
}
```

**示例上下文**：
```json
{
  "tasks": {
    "data_validation": {
      "status": "SUCCESS",
      "exitCode": 0,
      "output": {
        "validRecords": 9500,
        "invalidRecords": 500,
        "score": 95.0,
        "errors": []
      },
      "durationSeconds": 120
    }
  },
  "context": {
    "userId": 12345,
    "environment": "production",
    "batchId": "20260502"
  },
  "system": {
    "currentTime": "2026-05-02T10:30:00",
    "workflowInstanceId": 456
  }
}
```

**表达式示例**：
```java
// 检查数据质量分数
${tasks.data_validation.output.score >= 90}

// 检查任务是否成功
${tasks.data_validation.status == 'SUCCESS'}

// 检查错误数量
${tasks.data_validation.output.invalidRecords < 100}

// 组合条件
${tasks.data_validation.status == 'SUCCESS' 
  and tasks.data_validation.output.score >= 90}

// 检查环境
${context.environment == 'production'}

// 用户分组（A/B测试）
${context.userId % 2 == 0}
```

---

## 4. 数据模型扩展

### 4.1 表结构扩展

#### workflow_task_dependency表增加条件字段
```sql
ALTER TABLE task_dependency
ADD COLUMN condition VARCHAR(500) COMMENT '执行条件(SpEL表达式)';

-- 示例数据
INSERT INTO task_dependency (workflow_id, from_task_name, to_task_name, condition)
VALUES 
  (1, 'data_validation', 'auto_import', 
   '${tasks.data_validation.output.score >= 90}'),
  
  (1, 'data_validation', 'manual_review', 
   '${tasks.data_validation.output.score < 90}');
```

### 4.2 DAG JSON格式扩展

```json
{
  "version": "1.0",
  "tasks": [
    {
      "name": "data_validation",
      "displayName": "数据验证",
      "type": "python",
      "script": "validate.py"
    },
    {
      "name": "auto_import",
      "displayName": "自动导入",
      "type": "shell",
      "command": "python import.py"
    },
    {
      "name": "manual_review",
      "displayName": "人工审核",
      "type": "shell",
      "command": "python notify_review.py"
    }
  ],
  "dependencies": [
    {
      "from": "data_validation",
      "to": "auto_import",
      "condition": "${tasks.data_validation.output.score >= 90}"
    },
    {
      "from": "data_validation",
      "to": "manual_review",
      "condition": "${tasks.data_validation.output.score < 90}"
    }
  ]
}
```

### 4.3 Entity扩展

```java
@Data
public class WorkflowDependency {
    private String from;        // 上游任务
    private String to;          // 下游任务
    private String condition;   // 执行条件（可选）
}
```

---

## 5. 核心实现

### 5.1 条件表达式解析器

```java
@Service
@Slf4j
public class ConditionEvaluator {
    
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final StandardEvaluationContext evaluationContext = new StandardEvaluationContext();
    
    /**
     * 评估条件表达式
     * 
     * @param conditionExpr 条件表达式（SpEL）
     * @param context 上下文数据
     * @return 条件是否满足
     */
    public boolean evaluate(String conditionExpr, ConditionContext context) {
        if (conditionExpr == null || conditionExpr.isEmpty()) {
            // 没有条件，默认执行
            return true;
        }
        
        try {
            // 设置上下文变量
            evaluationContext.setVariable("tasks", context.getTasks());
            evaluationContext.setVariable("context", context.getContext());
            evaluationContext.setVariable("system", context.getSystem());
            
            // 解析并执行表达式
            Expression expression = parser.parseExpression(conditionExpr);
            Object result = expression.getValue(evaluationContext);
            
            // 转换为布尔值
            if (result instanceof Boolean) {
                return (Boolean) result;
            } else {
                log.warn("条件表达式返回非布尔值 expr={} result={}", conditionExpr, result);
                return false;
            }
            
        } catch (Exception e) {
            log.error("条件表达式评估失败 expr={}", conditionExpr, e);
            throw new RuntimeException("条件表达式评估失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证条件表达式语法
     */
    public void validate(String conditionExpr) {
        if (conditionExpr == null || conditionExpr.isEmpty()) {
            return;
        }
        
        try {
            parser.parseExpression(conditionExpr);
            log.info("条件表达式语法验证通过 expr={}", conditionExpr);
        } catch (Exception e) {
            throw new IllegalArgumentException("条件表达式语法错误: " + e.getMessage());
        }
    }
}
```

### 5.2 条件上下文构建

```java
@Service
@Slf4j
public class ConditionContextBuilder {
    
    @Autowired
    private WorkflowTaskInstanceMapper taskInstanceMapper;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 构建条件上下文
     */
    public ConditionContext buildContext(Long workflowInstanceId) {
        ConditionContext context = new ConditionContext();
        
        // 1. 加载任务执行结果
        Map<String, TaskResult> tasks = loadTaskResults(workflowInstanceId);
        context.setTasks(tasks);
        
        // 2. 加载工作流上下文（用户传入的参数）
        Map<String, Object> workflowContext = loadWorkflowContext(workflowInstanceId);
        context.setContext(workflowContext);
        
        // 3. 设置系统变量
        Map<String, Object> system = new HashMap<>();
        system.put("currentTime", LocalDateTime.now());
        system.put("workflowInstanceId", workflowInstanceId);
        context.setSystem(system);
        
        return context;
    }
    
    /**
     * 加载任务执行结果
     */
    private Map<String, TaskResult> loadTaskResults(Long workflowInstanceId) {
        List<WorkflowTaskInstance> taskInstances = 
            taskInstanceMapper.selectByInstanceId(workflowInstanceId);
        
        Map<String, TaskResult> results = new HashMap<>();
        
        for (WorkflowTaskInstance task : taskInstances) {
            // 只加载已完成的任务
            if (task.getStatus() != TaskInstanceStatus.SUCCESS &&
                task.getStatus() != TaskInstanceStatus.FAILED) {
                continue;
            }
            
            TaskResult result = new TaskResult();
            result.setStatus(task.getStatus().name());
            result.setExitCode(task.getExitCode() != null ? task.getExitCode() : -1);
            result.setDurationSeconds(
                task.getDurationSeconds() != null ? task.getDurationSeconds() : 0L
            );
            
            // 解析任务输出（假设是JSON格式）
            if (task.getOutput() != null && !task.getOutput().isEmpty()) {
                try {
                    Map<String, Object> output = 
                        objectMapper.readValue(task.getOutput(), 
                            new TypeReference<Map<String, Object>>() {});
                    result.setOutput(output);
                } catch (Exception e) {
                    log.warn("解析任务输出失败 taskName={}", task.getTaskName(), e);
                    result.setOutput(new HashMap<>());
                }
            } else {
                result.setOutput(new HashMap<>());
            }
            
            results.put(task.getTaskName(), result);
        }
        
        return results;
    }
    
    /**
     * 加载工作流上下文
     */
    private Map<String, Object> loadWorkflowContext(Long workflowInstanceId) {
        // 从工作流实例获取用户传入的上下文参数
        // TODO: 实现工作流实例的context字段存储
        return new HashMap<>();
    }
}

/**
 * 条件上下文
 */
@Data
public class ConditionContext {
    private Map<String, TaskResult> tasks;
    private Map<String, Object> context;
    private Map<String, Object> system;
}

/**
 * 任务执行结果
 */
@Data
public class TaskResult {
    private String status;
    private Integer exitCode;
    private Map<String, Object> output;
    private Long durationSeconds;
}
```

### 5.3 工作流执行引擎集成

```java
@Service
@Slf4j
public class WorkflowExecutorWithCondition extends WorkflowExecutor {
    
    @Autowired
    private ConditionEvaluator conditionEvaluator;
    
    @Autowired
    private ConditionContextBuilder contextBuilder;
    
    /**
     * 执行一层任务（支持条件分支）
     */
    @Override
    protected boolean executeLayer(WorkflowInstance instance, TaskLayer layer) 
            throws Exception {
        
        List<WorkflowTaskInstance> taskInstances = 
            workflowTaskInstanceMapper.selectByInstanceIdAndLayer(
                instance.getId(), layer.getLayerIndex()
            );
        
        if (taskInstances.isEmpty()) {
            return true;
        }
        
        // 构建条件上下文
        ConditionContext conditionContext = contextBuilder.buildContext(instance.getId());
        
        CountDownLatch latch = new CountDownLatch(taskInstances.size());
        AtomicBoolean layerSuccess = new AtomicBoolean(true);
        
        for (WorkflowTaskInstance taskInstance : taskInstances) {
            executorService.submit(() -> {
                try {
                    // 检查任务是否应该执行（条件判断）
                    if (shouldExecuteTask(taskInstance, conditionContext)) {
                        boolean success = executeTask(instance, taskInstance);
                        if (!success) {
                            layerSuccess.set(false);
                        }
                    } else {
                        // 条件不满足，跳过任务
                        skipTask(taskInstance);
                        log.info("任务条件不满足，跳过执行 taskName={}", taskInstance.getTaskName());
                    }
                } catch (Exception e) {
                    log.error("任务执行异常 taskInstanceId={}", taskInstance.getId(), e);
                    layerSuccess.set(false);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        
        return layerSuccess.get();
    }
    
    /**
     * 判断任务是否应该执行
     */
    private boolean shouldExecuteTask(
            WorkflowTaskInstance taskInstance, 
            ConditionContext context) {
        
        // 获取任务的依赖关系和条件
        List<WorkflowDependency> dependencies = 
            getDependenciesToTask(taskInstance.getWorkflowInstanceId(), taskInstance.getTaskName());
        
        if (dependencies.isEmpty()) {
            // 没有依赖，直接执行
            return true;
        }
        
        // 检查所有依赖的条件
        for (WorkflowDependency dep : dependencies) {
            String condition = dep.getCondition();
            
            if (condition == null || condition.isEmpty()) {
                // 无条件依赖，检查上游任务是否成功
                TaskResult upstreamResult = context.getTasks().get(dep.getFrom());
                if (upstreamResult == null || !"SUCCESS".equals(upstreamResult.getStatus())) {
                    log.info("上游任务未成功，跳过任务 task={} upstream={}", 
                            taskInstance.getTaskName(), dep.getFrom());
                    return false;
                }
            } else {
                // 有条件依赖，评估条件
                boolean conditionMet = conditionEvaluator.evaluate(condition, context);
                if (!conditionMet) {
                    log.info("条件不满足，跳过任务 task={} condition={}", 
                            taskInstance.getTaskName(), condition);
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * 跳过任务
     */
    private void skipTask(WorkflowTaskInstance taskInstance) {
        taskInstance.setStatus(TaskInstanceStatus.SKIPPED);
        taskInstance.setStartTime(LocalDateTime.now());
        taskInstance.setEndTime(LocalDateTime.now());
        taskInstance.setDurationSeconds(0);
        workflowTaskInstanceMapper.updateById(taskInstance);
        
        // 更新工作流实例的跳过计数
        workflowInstanceMapper.incrementSkippedTasks(taskInstance.getWorkflowInstanceId());
    }
    
    /**
     * 获取指向某个任务的所有依赖
     */
    private List<WorkflowDependency> getDependenciesToTask(Long workflowInstanceId, String taskName) {
        // 从工作流定义中获取依赖关系
        WorkflowInstance instance = workflowInstanceMapper.selectById(workflowInstanceId);
        WorkflowExecutionPlan plan = parseExecutionPlan(instance.getExecutionPlan());
        
        // 从原始DAG获取依赖
        // TODO: 在执行计划中保存依赖关系
        return new ArrayList<>();
    }
}
```

### 5.4 任务输出标准化

**为了支持条件判断，任务输出需要标准化为JSON格式**：

```python
# Python任务示例：数据验证
import json
import sys

def validate_data():
    # 执行验证逻辑
    total_records = 10000
    valid_records = 9500
    invalid_records = 500
    score = (valid_records / total_records) * 100
    
    # 输出标准JSON格式
    result = {
        "validRecords": valid_records,
        "invalidRecords": invalid_records,
        "totalRecords": total_records,
        "score": score,
        "status": "completed",
        "errors": []
    }
    
    print(json.dumps(result))
    
    return 0 if score >= 90 else 1

if __name__ == "__main__":
    sys.exit(validate_data())
```

```bash
# Shell任务示例：模型训练
#!/bin/bash

# 训练模型
python train.py

# 评估模型
accuracy=$(python evaluate.py | grep "accuracy" | awk '{print $2}')

# 输出JSON
echo "{\"accuracy\": $accuracy, \"modelPath\": \"/models/model_v1.pkl\"}"

# 根据精度返回退出码
if (( $(echo "$accuracy >= 0.95" | bc -l) )); then
    exit 0
else
    exit 1
fi
```

---

## 6. API设计

### 6.1 创建带条件分支的工作流

```http
POST /api/workflow
Content-Type: application/json

{
  "name": "数据质量检查工作流",
  "projectId": 100,
  "dagJson": {
    "tasks": [
      {
        "name": "data_validation",
        "displayName": "数据验证",
        "type": "python",
        "script": "validate.py"
      },
      {
        "name": "auto_import",
        "displayName": "自动导入",
        "type": "shell",
        "command": "python import.py"
      },
      {
        "name": "manual_review",
        "displayName": "人工审核",
        "type": "shell",
        "command": "python notify.py"
      }
    ],
    "dependencies": [
      {
        "from": "data_validation",
        "to": "auto_import",
        "condition": "${tasks.data_validation.output.score >= 90}"
      },
      {
        "from": "data_validation",
        "to": "manual_review",
        "condition": "${tasks.data_validation.output.score < 90}"
      }
    ]
  }
}
```

### 6.2 验证条件表达式

```http
POST /api/workflow/condition/validate
Content-Type: application/json

{
  "expression": "${tasks.data_validation.output.score >= 90}"
}

Response:
{
  "code": 200,
  "data": {
    "valid": true,
    "message": "表达式语法正确"
  }
}
```

### 6.3 测试条件表达式

```http
POST /api/workflow/condition/evaluate
Content-Type: application/json

{
  "expression": "${tasks.data_validation.output.score >= 90}",
  "context": {
    "tasks": {
      "data_validation": {
        "status": "SUCCESS",
        "output": {
          "score": 95.0
        }
      }
    }
  }
}

Response:
{
  "code": 200,
  "data": {
    "result": true,
    "message": "条件满足"
  }
}
```

---

## 7. 高级特性

### 7.1 多路分支（Switch-Case）

```json
{
  "dependencies": [
    {
      "from": "check_amount",
      "to": "auto_approve",
      "condition": "${tasks.check_amount.output.amount <= 1000}"
    },
    {
      "from": "check_amount",
      "to": "manager_approve",
      "condition": "${tasks.check_amount.output.amount > 1000 and tasks.check_amount.output.amount <= 10000}"
    },
    {
      "from": "check_amount",
      "to": "director_approve",
      "condition": "${tasks.check_amount.output.amount > 10000}"
    }
  ]
}
```

### 7.2 条件组合（AND/OR）

```json
{
  "dependencies": [
    {
      "from": "task_a",
      "to": "task_c",
      "condition": "${tasks.task_a.status == 'SUCCESS' and tasks.task_b.status == 'SUCCESS'}"
    },
    {
      "from": "task_a",
      "to": "task_d",
      "condition": "${tasks.task_a.status == 'FAILED' or tasks.task_b.status == 'FAILED'}"
    }
  ]
}
```

### 7.3 默认分支（Else）

```json
{
  "dependencies": [
    {
      "from": "data_validation",
      "to": "auto_import",
      "condition": "${tasks.data_validation.output.score >= 90}"
    },
    {
      "from": "data_validation",
      "to": "manual_review",
      "condition": "${tasks.data_validation.output.score < 90 or tasks.data_validation.status == 'FAILED'}"
    }
  ]
}
```

### 7.4 循环控制（配合重试）

```java
// 任务重试直到成功或达到最大次数
@Data
public class WorkflowTask {
    private String name;
    private String type;
    private String command;
    
    // 循环控制
    private LoopConfig loopConfig;
}

@Data
public class LoopConfig {
    private int maxIterations;                // 最大迭代次数
    private String continueCondition;         // 继续条件
    private int intervalSeconds;              // 间隔时间
}
```

```json
{
  "name": "retry_until_success",
  "type": "shell",
  "command": "python check_status.py",
  "loopConfig": {
    "maxIterations": 10,
    "continueCondition": "${tasks.retry_until_success.output.ready == false}",
    "intervalSeconds": 60
  }
}
```

---

## 8. 监控与可视化

### 8.1 条件判断日志

```java
@Aspect
@Component
@Slf4j
public class ConditionEvaluationAspect {
    
    @Around("@annotation(com.imperium.annotation.EvaluateCondition)")
    public Object logConditionEvaluation(ProceedingJoinPoint pjp) throws Throwable {
        String condition = getConditionExpression(pjp);
        
        log.info("开始评估条件 condition={}", condition);
        
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long elapsed = System.currentTimeMillis() - start;
        
        log.info("条件评估完成 condition={} result={} elapsed={}ms", 
                condition, result, elapsed);
        
        return result;
    }
}
```

### 8.2 执行路径可视化

```
工作流实例执行路径：
┌─────────────────────────────────────────┐
│  实例ID: 456                             │
│  工作流: 数据质量检查                     │
├─────────────────────────────────────────┤
│                                         │
│  ✅ data_validation (Layer 0)           │
│     └─ score: 95.0                      │
│                                         │
│  ✅ auto_import (Layer 1)               │
│     └─ 条件满足: score >= 90            │
│                                         │
│  ⏭️ manual_review (Layer 1)             │
│     └─ 条件不满足: score < 90 (跳过)    │
│                                         │
└─────────────────────────────────────────┘
```

### 8.3 分支覆盖率统计

```java
@Service
public class WorkflowAnalyticsService {
    
    /**
     * 统计分支覆盖率
     */
    public BranchCoverageReport analyzeBranchCoverage(Long workflowId) {
        List<WorkflowInstance> instances = 
            workflowInstanceMapper.selectByWorkflowId(workflowId);
        
        // 统计每个条件分支的执行次数
        Map<String, Integer> branchExecutionCount = new HashMap<>();
        
        for (WorkflowInstance instance : instances) {
            List<WorkflowTaskInstance> tasks = 
                workflowTaskInstanceMapper.selectByInstanceId(instance.getId());
            
            for (WorkflowTaskInstance task : tasks) {
                String key = task.getTaskName() + ":" + task.getStatus();
                branchExecutionCount.merge(key, 1, Integer::sum);
            }
        }
        
        BranchCoverageReport report = new BranchCoverageReport();
        report.setWorkflowId(workflowId);
        report.setTotalInstances(instances.size());
        report.setBranchStats(branchExecutionCount);
        
        return report;
    }
}
```

**输出示例**：
```
分支覆盖率报告：
┌──────────────────────────────────────────┐
│  工作流: 数据质量检查                      │
│  总实例数: 100                            │
├──────────────────────────────────────────┤
│  auto_import:SUCCESS       → 85次 (85%)  │
│  manual_review:SUCCESS     → 15次 (15%)  │
│                                          │
│  结论: 85%的数据质量合格，直接导入         │
└──────────────────────────────────────────┘
```

---

## 9. 测试方案

### 9.1 单元测试

```java
@SpringBootTest
public class ConditionEvaluatorTest {
    
    @Autowired
    private ConditionEvaluator evaluator;
    
    @Test
    public void testSimpleCondition() {
        ConditionContext context = new ConditionContext();
        
        TaskResult taskA = new TaskResult();
        taskA.setStatus("SUCCESS");
        taskA.setOutput(Map.of("score", 95.0));
        
        context.setTasks(Map.of("task_a", taskA));
        context.setContext(new HashMap<>());
        context.setSystem(new HashMap<>());
        
        // 测试条件评估
        boolean result = evaluator.evaluate(
            "${tasks.task_a.output.score >= 90}", context
        );
        
        assertTrue(result);
    }
    
    @Test
    public void testComplexCondition() {
        ConditionContext context = buildTestContext();
        
        // AND条件
        boolean result1 = evaluator.evaluate(
            "${tasks.task_a.status == 'SUCCESS' and tasks.task_b.output.count > 100}", 
            context
        );
        assertTrue(result1);
        
        // OR条件
        boolean result2 = evaluator.evaluate(
            "${tasks.task_a.status == 'FAILED' or tasks.task_b.output.count < 50}", 
            context
        );
        assertFalse(result2);
    }
    
    @Test
    public void testInvalidExpression() {
        ConditionContext context = buildTestContext();
        
        assertThrows(RuntimeException.class, () -> {
            evaluator.evaluate("${invalid expression}", context);
        });
    }
}
```

### 9.2 集成测试

```java
@SpringBootTest
public class ConditionalWorkflowTest {
    
    @Autowired
    private WorkflowInstanceService instanceService;
    
    @Autowired
    private WorkflowExecutor executor;
    
    @Test
    public void testConditionalBranch_ConditionMet() {
        // 创建工作流实例（条件满足场景）
        Long instanceId = createWorkflowWithHighScore();
        
        executor.executeWorkflowInstance(instanceId);
        
        // 验证自动导入任务被执行
        WorkflowTaskInstance autoImport = 
            workflowTaskInstanceMapper.selectByInstanceAndName(instanceId, "auto_import");
        assertEquals(TaskInstanceStatus.SUCCESS, autoImport.getStatus());
        
        // 验证人工审核任务被跳过
        WorkflowTaskInstance manualReview = 
            workflowTaskInstanceMapper.selectByInstanceAndName(instanceId, "manual_review");
        assertEquals(TaskInstanceStatus.SKIPPED, manualReview.getStatus());
    }
    
    @Test
    public void testConditionalBranch_ConditionNotMet() {
        // 创建工作流实例（条件不满足场景）
        Long instanceId = createWorkflowWithLowScore();
        
        executor.executeWorkflowInstance(instanceId);
        
        // 验证自动导入任务被跳过
        WorkflowTaskInstance autoImport = 
            workflowTaskInstanceMapper.selectByInstanceAndName(instanceId, "auto_import");
        assertEquals(TaskInstanceStatus.SKIPPED, autoImport.getStatus());
        
        // 验证人工审核任务被执行
        WorkflowTaskInstance manualReview = 
            workflowTaskInstanceMapper.selectByInstanceAndName(instanceId, "manual_review");
        assertEquals(TaskInstanceStatus.SUCCESS, manualReview.getStatus());
    }
}
```

---

## 10. 性能优化

### 10.1 条件表达式缓存

```java
@Service
public class ConditionEvaluatorWithCache extends ConditionEvaluator {
    
    private final Cache<String, Expression> expressionCache = 
        CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
    
    @Override
    public boolean evaluate(String conditionExpr, ConditionContext context) {
        if (conditionExpr == null || conditionExpr.isEmpty()) {
            return true;
        }
        
        try {
            // 从缓存获取已解析的表达式
            Expression expression = expressionCache.get(conditionExpr, () -> {
                return parser.parseExpression(conditionExpr);
            });
            
            // 设置上下文并执行
            evaluationContext.setVariable("tasks", context.getTasks());
            evaluationContext.setVariable("context", context.getContext());
            evaluationContext.setVariable("system", context.getSystem());
            
            Object result = expression.getValue(evaluationContext);
            return result instanceof Boolean ? (Boolean) result : false;
            
        } catch (Exception e) {
            log.error("条件表达式评估失败 expr={}", conditionExpr, e);
            throw new RuntimeException("条件表达式评估失败: " + e.getMessage());
        }
    }
}
```

### 10.2 并行条件评估

```java
/**
 * 对同一层的多个条件并行评估
 */
public Map<String, Boolean> evaluateBatch(
        List<String> conditions, 
        ConditionContext context) {
    
    return conditions.parallelStream()
        .collect(Collectors.toMap(
            cond -> cond,
            cond -> evaluate(cond, context)
        ));
}
```

---

## 11. 常见问题

### Q1: 条件表达式如何调试？

**答**：提供条件评估API
```http
POST /api/workflow/condition/debug
{
  "expression": "${tasks.task_a.output.score >= 90}",
  "context": {...}
}
```

返回详细的评估过程和中间变量值。

### Q2: 如何处理条件表达式错误？

**答**：
1. 工作流创建时验证语法
2. 执行时捕获异常，记录详细日志
3. 默认策略：表达式错误 → 跳过任务

### Q3: 条件分支会影响拓扑排序吗？

**答**：不影响。拓扑排序基于依赖关系，条件分支只影响运行时的执行决策。

### Q4: 如何实现"等待某个条件满足"的场景？

**答**：使用循环配置 + 条件检查
```json
{
  "loopConfig": {
    "maxIterations": 10,
    "continueCondition": "${tasks.check.output.ready == false}",
    "intervalSeconds": 60
  }
}
```

---

## 12. 后续优化方向

### 12.1 自定义函数

```java
// 注册自定义函数
evaluationContext.registerFunction("isWeekend", 
    DateUtils.class.getDeclaredMethod("isWeekend", Date.class));

// 使用自定义函数
${#isWeekend(system.currentTime)}
```

### 12.2 条件模板

```java
// 预定义常用条件
Map<String, String> conditionTemplates = Map.of(
    "task_success", "${tasks.{taskName}.status == 'SUCCESS'}",
    "score_high", "${tasks.{taskName}.output.score >= {threshold}}",
    "is_production", "${context.environment == 'production'}"
);
```

### 12.3 可视化条件编辑器

```
┌─────────────────────────────────────────┐
│  条件表达式编辑器                        │
├─────────────────────────────────────────┤
│                                         │
│  [任务] [task_a] [的输出] [score]       │
│  [运算符] [>=]                          │
│  [值] [90]                              │
│                                         │
│  预览: ${tasks.task_a.output.score >= 90}│
│                                         │
│  [验证] [测试] [保存]                    │
└─────────────────────────────────────────┘
```

---

## 13. 总结

条件分支是工作流引擎的高级特性，价值：

✅ **智能决策**：根据运行时结果动态选择执行路径  
✅ **业务灵活性**：满足复杂的业务逻辑需求  
✅ **资源优化**：跳过不必要的任务，节省资源  
✅ **可扩展性**：支持自定义函数和复杂表达式  

**关键设计决策**：
- 使用SpEL表达式（功能强大，易于扩展）
- 标准化任务输出（JSON格式）
- 条件上下文（tasks + context + system）
- 表达式缓存（提升性能）
- 跳过而非删除（保留执行轨迹）

**实际效果**：
- 简单if-else：2个分支，1个被跳过
- 多路switch：N个分支，只执行1个
- 复杂业务：审批流程、A/B测试、智能路由

**适用场景**：
- ✅ 数据质量检查
- ✅ 模型训练与部署
- ✅ 业务审批流程
- ✅ A/B测试
- ✅ 智能告警

这个设计让工作流引擎从"静态DAG"升级为"智能工作流"，为企业级业务流程自动化提供了强大的决策能力。🚀
