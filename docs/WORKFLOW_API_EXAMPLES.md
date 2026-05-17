# Workflow API 使用示例

本文档提供了工作流 API 的完整使用示例，展示如何基于重构后的设计创建和管理工作流。

## 前提条件

### 1. 创建可复用的任务定义

在创建工作流之前，需要先在 `task` 表中定义可复用的任务：

```bash
POST /api/tasks
Content-Type: application/json

{
  "projectId": 1,
  "taskName": "数据验证",
  "taskCode": "data_validation",
  "taskType": "SHELL",
  "executorConfig": {
    "command": "python /scripts/validate_data.py --input ${input_path} --rules ${validation_rules}"
  },
  "scheduleType": "DEPENDENCY",
  "timeoutSeconds": 3600,
  "retryTimes": 3,
  "retryInterval": 60,
  "priority": 5,
  "resourceRequirement": {
    "cpuCores": 2,
    "memoryMb": 4096
  },
  "alertOnFailure": 1,
  "description": "验证输入数据的完整性和格式"
}

# 返回
{
  "code": 200,
  "message": "创建成功",
  "data": {
    "id": 101
  }
}
```

继续创建其他任务：

```bash
# 特征提取任务
POST /api/tasks
{
  "projectId": 1,
  "taskName": "特征提取",
  "taskCode": "feature_extraction",
  "taskType": "PYTHON",
  "executorConfig": {
    "script": "/scripts/extract_features.py",
    "args": ["--config", "${feature_config}", "--output", "${output_path}"]
  },
  "timeoutSeconds": 7200,
  "retryTimes": 2,
  "retryInterval": 120,
  "priority": 5,
  "resourceRequirement": {
    "cpuCores": 4,
    "memoryMb": 8192
  }
}
# 返回 id: 102

# 模型训练任务
POST /api/tasks
{
  "projectId": 1,
  "taskName": "模型训练",
  "taskCode": "model_training",
  "taskType": "DOCKER",
  "executorConfig": {
    "image": "ml-training:latest",
    "command": ["python", "train.py"],
    "env": {
      "MODEL_TYPE": "${model_type}",
      "EPOCHS": "${epochs}"
    }
  },
  "timeoutSeconds": 14400,
  "retryTimes": 1,
  "retryInterval": 0,
  "priority": 8,
  "resourceRequirement": {
    "cpuCores": 16,
    "memoryMb": 32768,
    "gpuCount": 2
  }
}
# 返回 id: 103
```

## 示例 1：创建简单的线性工作流

创建一个简单的线性工作流：数据验证 → 特征提取 → 模型训练

```bash
POST /api/workflows
Content-Type: application/json

{
  "projectId": 1,
  "workflowName": "机器学习训练流水线",
  "workflowCode": "ml_training_pipeline",
  "description": "完整的机器学习训练流程：数据验证 → 特征提取 → 模型训练",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "validate_data",
        "taskId": 101,
        "displayName": "验证训练数据",
        "paramOverrides": {
          "input_path": "/data/training/batch_001",
          "validation_rules": "strict"
        }
      },
      {
        "nodeName": "extract_features",
        "taskId": 102,
        "displayName": "提取特征",
        "paramOverrides": {
          "feature_config": "/configs/features_v2.json",
          "output_path": "/data/features/batch_001"
        },
        "resourceOverride": {
          "cpuCores": 8,
          "memoryMb": 16384
        }
      },
      {
        "nodeName": "train_model",
        "taskId": 103,
        "displayName": "训练模型",
        "paramOverrides": {
          "model_type": "xgboost",
          "epochs": 100
        },
        "timeoutOverride": 28800,
        "retryOverride": {
          "maxRetries": 2,
          "retryInterval": 300
        }
      }
    ],
    "dependencies": [
      {
        "from": "validate_data",
        "to": "extract_features",
        "condition": "${tasks.validate_data.exitCode == 0}"
      },
      {
        "from": "extract_features",
        "to": "train_model",
        "condition": "${tasks.extract_features.status == 'SUCCESS'}"
      }
    ]
  },
  "scheduleType": "CRON",
  "cronExpression": "0 2 * * *",
  "timeoutSeconds": 43200,
  "alertOnFailure": 1
}
```

返回：

```json
{
  "code": 200,
  "message": "工作流创建成功",
  "data": {
    "id": 1001,
    "workflowName": "机器学习训练流水线",
    "workflowCode": "ml_training_pipeline",
    "status": 1,
    "taskCount": 3,
    "createdAt": "2026-05-03T10:30:00"
  }
}
```

## 示例 2：创建并行工作流

创建一个包含并行任务的工作流：

```
         ┌─> validate_data_a ─> train_model_a ─┐
start ─> │                                      ├─> compare_models
         └─> validate_data_b ─> train_model_b ─┘
```

```bash
POST /api/workflows
Content-Type: application/json

{
  "projectId": 1,
  "workflowName": "A/B模型对比测试",
  "workflowCode": "ab_test_pipeline",
  "description": "并行训练两个模型并对比效果",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "validate_data_a",
        "taskId": 101,
        "displayName": "验证实验组A数据",
        "paramOverrides": {
          "input_path": "/data/experiment_a",
          "validation_rules": "standard"
        }
      },
      {
        "nodeName": "validate_data_b",
        "taskId": 101,
        "displayName": "验证实验组B数据",
        "paramOverrides": {
          "input_path": "/data/experiment_b",
          "validation_rules": "standard"
        }
      },
      {
        "nodeName": "train_model_a",
        "taskId": 103,
        "displayName": "训练模型A（随机森林）",
        "paramOverrides": {
          "model_type": "random_forest",
          "epochs": 50
        }
      },
      {
        "nodeName": "train_model_b",
        "taskId": 103,
        "displayName": "训练模型B（XGBoost）",
        "paramOverrides": {
          "model_type": "xgboost",
          "epochs": 100
        }
      },
      {
        "nodeName": "compare_models",
        "taskId": 104,
        "displayName": "对比模型效果",
        "paramOverrides": {
          "model_a_path": "/models/random_forest",
          "model_b_path": "/models/xgboost",
          "test_data": "/data/test_set"
        }
      }
    ],
    "dependencies": [
      {"from": "validate_data_a", "to": "train_model_a"},
      {"from": "validate_data_b", "to": "train_model_b"},
      {"from": "train_model_a", "to": "compare_models"},
      {"from": "train_model_b", "to": "compare_models"}
    ]
  },
  "scheduleType": "MANUAL"
}
```

执行流程：
1. `validate_data_a` 和 `validate_data_b` 并行执行
2. `validate_data_a` 完成后执行 `train_model_a`
3. `validate_data_b` 完成后执行 `train_model_b`
4. 两个训练任务都完成后，执行 `compare_models`

## 示例 3：创建复杂的菱形依赖工作流

创建一个数据处理工作流，展示复杂的依赖关系：

```
                  ┌─> clean_user_data ─┐
                  │                     │
load_raw_data ─>  ├─> clean_order_data ├─> merge_data ─> generate_report
                  │                     │
                  └─> clean_product_data┘
```

```bash
POST /api/workflows
Content-Type: application/json

{
  "projectId": 1,
  "workflowName": "数据清洗与报表生成",
  "workflowCode": "data_cleaning_pipeline",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "load_raw_data",
        "taskId": 105,
        "displayName": "加载原始数据",
        "paramOverrides": {
          "source": "mysql://prod-db/raw_data",
          "date": "${date}"
        }
      },
      {
        "nodeName": "clean_user_data",
        "taskId": 106,
        "displayName": "清洗用户数据",
        "paramOverrides": {
          "input_table": "raw_users"
        }
      },
      {
        "nodeName": "clean_order_data",
        "taskId": 106,
        "displayName": "清洗订单数据",
        "paramOverrides": {
          "input_table": "raw_orders"
        }
      },
      {
        "nodeName": "clean_product_data",
        "taskId": 106,
        "displayName": "清洗商品数据",
        "paramOverrides": {
          "input_table": "raw_products"
        }
      },
      {
        "nodeName": "merge_data",
        "taskId": 107,
        "displayName": "合并清洗后的数据",
        "paramOverrides": {
          "tables": ["clean_users", "clean_orders", "clean_products"]
        }
      },
      {
        "nodeName": "generate_report",
        "taskId": 108,
        "displayName": "生成业务报表",
        "paramOverrides": {
          "report_type": "daily_summary",
          "output_format": "pdf"
        }
      }
    ],
    "dependencies": [
      {"from": "load_raw_data", "to": "clean_user_data"},
      {"from": "load_raw_data", "to": "clean_order_data"},
      {"from": "load_raw_data", "to": "clean_product_data"},
      {"from": "clean_user_data", "to": "merge_data"},
      {"from": "clean_order_data", "to": "merge_data"},
      {"from": "clean_product_data", "to": "merge_data"},
      {"from": "merge_data", "to": "generate_report"}
    ]
  },
  "scheduleType": "CRON",
  "cronExpression": "0 3 * * *"
}
```

## 示例 4：条件执行工作流

创建一个带条件执行的工作流：

```bash
POST /api/workflows
Content-Type: application/json

{
  "projectId": 1,
  "workflowName": "智能数据处理流程",
  "workflowCode": "smart_data_pipeline",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "check_data_quality",
        "taskId": 109,
        "displayName": "检查数据质量"
      },
      {
        "nodeName": "simple_processing",
        "taskId": 110,
        "displayName": "简单处理（质量高）"
      },
      {
        "nodeName": "deep_cleaning",
        "taskId": 111,
        "displayName": "深度清洗（质量低）"
      },
      {
        "nodeName": "final_output",
        "taskId": 112,
        "displayName": "输出结果"
      }
    ],
    "dependencies": [
      {
        "from": "check_data_quality",
        "to": "simple_processing",
        "condition": "${tasks.check_data_quality.output.quality_score >= 90}"
      },
      {
        "from": "check_data_quality",
        "to": "deep_cleaning",
        "condition": "${tasks.check_data_quality.output.quality_score < 90}"
      },
      {
        "from": "simple_processing",
        "to": "final_output"
      },
      {
        "from": "deep_cleaning",
        "to": "final_output"
      }
    ]
  }
}
```

执行逻辑：
- 如果数据质量分数 >= 90，走 `simple_processing` 分支
- 如果数据质量分数 < 90，走 `deep_cleaning` 分支
- 最后都汇聚到 `final_output`

## 示例 5：更新工作流

更新已有工作流的 DAG 定义：

```bash
PUT /api/workflows/1001
Content-Type: application/json

{
  "workflowName": "机器学习训练流水线 v2",
  "description": "增加了模型评估步骤",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "validate_data",
        "taskId": 101,
        "displayName": "验证训练数据",
        "paramOverrides": {
          "input_path": "/data/training/batch_001",
          "validation_rules": "strict"
        }
      },
      {
        "nodeName": "extract_features",
        "taskId": 102,
        "displayName": "提取特征",
        "paramOverrides": {
          "feature_config": "/configs/features_v2.json",
          "output_path": "/data/features/batch_001"
        }
      },
      {
        "nodeName": "train_model",
        "taskId": 103,
        "displayName": "训练模型",
        "paramOverrides": {
          "model_type": "xgboost",
          "epochs": 100
        }
      },
      {
        "nodeName": "evaluate_model",
        "taskId": 113,
        "displayName": "评估模型",
        "paramOverrides": {
          "test_data": "/data/test_set",
          "metrics": ["accuracy", "f1", "auc"]
        }
      }
    ],
    "dependencies": [
      {"from": "validate_data", "to": "extract_features"},
      {"from": "extract_features", "to": "train_model"},
      {"from": "train_model", "to": "evaluate_model"}
    ]
  }
}
```

## 示例 6：查询工作流详情

```bash
GET /api/workflows/1001

# 返回
{
  "code": 200,
  "data": {
    "id": 1001,
    "projectId": 1,
    "workflowName": "机器学习训练流水线",
    "workflowCode": "ml_training_pipeline",
    "description": "完整的机器学习训练流程",
    "dag": {
      "version": "1.0",
      "tasks": [
        {
          "nodeName": "validate_data",
          "taskId": 101,
          "displayName": "验证训练数据",
          "paramOverrides": {
            "input_path": "/data/training/batch_001",
            "validation_rules": "strict"
          }
        }
        // ... 其他任务
      ],
      "dependencies": [
        {
          "from": "validate_data",
          "to": "extract_features",
          "condition": "${tasks.validate_data.exitCode == 0}"
        }
        // ... 其他依赖
      ]
    },
    "scheduleType": "CRON",
    "cronExpression": "0 2 * * *",
    "nextScheduleTime": "2026-05-04T02:00:00",
    "timeoutSeconds": 43200,
    "alertOnFailure": 1,
    "status": 1,
    "taskCount": 3,
    "createdAt": "2026-05-03T10:30:00",
    "updatedAt": "2026-05-03T10:30:00"
  }
}
```

## 示例 7：任务复用的高级场景

同一个 Task 在不同节点中使用不同配置：

```bash
POST /api/workflows
Content-Type: application/json

{
  "projectId": 1,
  "workflowName": "多环境数据同步",
  "workflowCode": "multi_env_sync",
  "dagJson": {
    "version": "1.0",
    "tasks": [
      {
        "nodeName": "sync_to_dev",
        "taskId": 120,
        "displayName": "同步到开发环境",
        "paramOverrides": {
          "target_env": "dev",
          "target_host": "dev-db.example.com"
        },
        "resourceOverride": {
          "cpuCores": 2,
          "memoryMb": 4096
        },
        "timeoutOverride": 1800
      },
      {
        "nodeName": "sync_to_staging",
        "taskId": 120,
        "displayName": "同步到预发布环境",
        "paramOverrides": {
          "target_env": "staging",
          "target_host": "staging-db.example.com"
        },
        "resourceOverride": {
          "cpuCores": 4,
          "memoryMb": 8192
        },
        "timeoutOverride": 3600
      },
      {
        "nodeName": "sync_to_prod",
        "taskId": 120,
        "displayName": "同步到生产环境",
        "paramOverrides": {
          "target_env": "prod",
          "target_host": "prod-db.example.com"
        },
        "resourceOverride": {
          "cpuCores": 8,
          "memoryMb": 16384
        },
        "timeoutOverride": 7200,
        "retryOverride": {
          "maxRetries": 5,
          "retryInterval": 300,
          "backoffMultiplier": 2.0
        }
      }
    ],
    "dependencies": [
      {"from": "sync_to_dev", "to": "sync_to_staging"},
      {"from": "sync_to_staging", "to": "sync_to_prod"}
    ]
  }
}
```

在这个例子中：
- 三个节点都引用同一个 Task（taskId=120，数据同步任务）
- 但每个节点使用不同的参数、资源配置和超时时间
- 生产环境节点还使用了更激进的重试策略

## 关键设计说明

### 1. nodeName vs taskId

- **nodeName**：节点在 DAG 中的唯一标识，用于建立依赖关系
- **taskId**：引用的 Task 实体 ID，决定了任务的实际执行逻辑

同一个 taskId 可以在多个节点中使用，只要 nodeName 不同即可。

### 2. 参数覆盖优先级

执行时的最终参数合并顺序：

1. Task.executorConfig（基础配置）
2. WorkflowTask.paramOverrides（工作流级别覆盖）
3. 运行时参数（触发时传入）

### 3. 资源覆盖

如果 WorkflowTask 设置了 resourceOverride，则使用覆盖值；否则使用 Task 中定义的默认资源需求。

### 4. 条件执行

依赖关系的 condition 字段支持 SpEL 表达式，可以访问：
- `tasks.{nodeName}.status`：任务状态
- `tasks.{nodeName}.exitCode`：退出码
- `tasks.{nodeName}.output`：任务输出（JSON）

## 最佳实践

1. **任务粒度**：Task 应该是通用的、可复用的原子任务
2. **参数化**：Task 的 executorConfig 中使用 `${param}` 占位符，通过 paramOverrides 传入具体值
3. **命名规范**：
   - taskCode：使用下划线命名，如 `data_validation`
   - nodeName：使用下划线命名，描述在工作流中的作用，如 `validate_input_data`
4. **依赖关系**：尽量使用条件执行，避免无脑串行
5. **资源覆盖**：仅在必要时使用，保持 Task 的默认配置作为基准

## 错误处理

### DAG 验证失败

```json
{
  "code": 400,
  "message": "DAG验证失败: 检测到循环依赖，DAG无效"
}
```

### 引用的 Task 不存在

```json
{
  "code": 400,
  "message": "DAG验证失败: 引用的任务不存在，taskId=999"
}
```

### 节点名称重复

```json
{
  "code": 400,
  "message": "DAG验证失败: 节点名称重复: validate_data"
}
```

### 依赖引用的节点不存在

```json
{
  "code": 400,
  "message": "DAG验证失败: 依赖的上游节点不存在: non_existent_node"
}
```
