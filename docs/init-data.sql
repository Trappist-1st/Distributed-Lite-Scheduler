-- ============================================================================
-- 分布式轻量级调度系统 - 初始化数据脚本
-- Initial Data for Distributed Lite Scheduler
-- 版本: v1.0
-- 创建日期: 2026-04-02
-- ============================================================================

-- 设置字符集
SET NAMES utf8mb4;

-- ============================================================================
-- 1. 用户数据
-- ============================================================================

-- 插入管理员用户
INSERT INTO `user` (`id`, `username`, `email`, `password_hash`, `nickname`, `status`) VALUES
(1, 'admin', 'admin@scheduler.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 1),
(2, 'alice', 'alice@example.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'Alice Zhang', 1),
(3, 'bob', 'bob@example.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'Bob Wang', 1),
(4, 'charlie', 'charlie@example.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'Charlie Li', 1);

-- ============================================================================
-- 2. 租户数据
-- ============================================================================

-- 插入租户
INSERT INTO `tenant` (`id`, `tenant_name`, `tenant_code`, `owner_user_id`, `description`, `status`, `max_projects`, `max_tasks`) VALUES
(1, '数据团队', 'data-team', 1, '负责数据ETL和模型训练', 1, 20, 2000),
(2, 'AI研发团队', 'ai-team', 2, 'AI模型研发和训练', 1, 15, 1500),
(3, '测试团队', 'test-team', 3, '系统测试和压力测试', 1, 10, 1000);

-- 插入租户成员
INSERT INTO `tenant_member` (`tenant_id`, `user_id`, `role`) VALUES
(1, 1, 'OWNER'),
(1, 2, 'ADMIN'),
(1, 3, 'MEMBER'),
(2, 2, 'OWNER'),
(2, 4, 'ADMIN'),
(3, 3, 'OWNER');

-- ============================================================================
-- 3. 资源配额数据
-- ============================================================================

INSERT INTO `resource_quota` (`tenant_id`, `max_cpu`, `max_memory_mb`, `max_gpu`, `max_running_tasks`, `max_pending_tasks`) VALUES
(1, 32, 102400, 4, 100, 1000),
(2, 64, 204800, 8, 200, 2000),
(3, 16, 51200, 2, 50, 500);

-- ============================================================================
-- 4. 资源节点数据
-- ============================================================================

INSERT INTO `resource_node` (`id`, `node_name`, `node_host`, `node_port`, `node_type`, `total_cpu`, `total_memory_mb`, `total_gpu`, `gpu_model`, `available_cpu`, `available_memory_mb`, `available_gpu`, `status`, `last_heartbeat_time`, `labels`) VALUES
(1, 'worker-node-01', '192.168.1.101', 8080, 'CPU', 16, 32768, 0, NULL, 16, 32768, 0, 'ONLINE', NOW(), '{"zone":"us-west-1","env":"prod"}'),
(2, 'worker-node-02', '192.168.1.102', 8080, 'CPU', 16, 32768, 0, NULL, 16, 32768, 0, 'ONLINE', NOW(), '{"zone":"us-west-1","env":"prod"}'),
(3, 'gpu-node-01', '192.168.1.201', 8080, 'GPU', 32, 131072, 4, 'V100', 32, 131072, 4, 'ONLINE', NOW(), '{"zone":"us-west-2","env":"prod","gpu":"v100"}'),
(4, 'gpu-node-02', '192.168.1.202', 8080, 'GPU', 32, 131072, 4, 'A100', 32, 131072, 4, 'ONLINE', NOW(), '{"zone":"us-west-2","env":"prod","gpu":"a100"}'),
(5, 'mixed-node-01', '192.168.1.301', 8080, 'MIXED', 64, 262144, 2, 'RTX3090', 64, 262144, 2, 'ONLINE', NOW(), '{"zone":"us-east-1","env":"staging"}');

-- ============================================================================
-- 5. 项目数据
-- ============================================================================

INSERT INTO `project` (`id`, `tenant_id`, `project_name`, `project_code`, `description`, `creator_user_id`, `status`, `extra_config`) VALUES
(1, 1, '数据仓库ETL', 'dwh-etl', '每日数据仓库ETL任务', 1, 1, '{"notification_email":"data-team@example.com"}'),
(2, 1, '用户行为分析', 'user-analytics', '用户行为数据分析和报表生成', 2, 1, NULL),
(3, 2, 'CV模型训练', 'cv-training', '计算机视觉模型训练', 2, 1, '{"gpu_required":true}'),
(4, 2, 'NLP实验', 'nlp-exp', '自然语言处理实验', 4, 1, NULL),
(5, 3, '性能测试', 'perf-test', '系统性能和压力测试', 3, 1, NULL);

-- ============================================================================
-- 6. 任务定义数据
-- ============================================================================

INSERT INTO `task` (`id`, `project_id`, `task_name`, `task_code`, `task_type`, `executor_config`, `schedule_type`, `cron_expression`, `timeout_seconds`, `retry_times`, `retry_interval`, `priority`, `resource_require`, `alert_on_failure`, `alert_on_timeout`, `description`, `creator_user_id`, `status`) VALUES
-- 数据仓库ETL任务
(1, 1, '数据抽取', 'extract-data', 'SHELL', '{"type":"SHELL","command":"python /opt/etl/extract.py","workdir":"/opt/etl"}', 'CRON', '0 2 * * *', 3600, 2, 60, 8, '{"cpu":4,"memory_mb":8192}', 1, 1, '从源系统抽取数据', 1, 1),
(2, 1, '数据清洗', 'clean-data', 'PYTHON', '{"type":"PYTHON","script":"/opt/etl/clean.py","python_version":"3.9"}', 'DEPENDENCY', NULL, 7200, 1, 120, 7, '{"cpu":8,"memory_mb":16384}', 1, 1, '数据清洗和标准化', 1, 1),
(3, 1, '数据加载', 'load-data', 'SHELL', '{"type":"SHELL","command":"python /opt/etl/load.py"}', 'DEPENDENCY', NULL, 1800, 2, 60, 6, '{"cpu":4,"memory_mb":8192}', 1, 1, '加载数据到数据仓库', 1, 1),

-- 用户行为分析任务
(4, 2, '用户访问统计', 'user-visit-stats', 'SHELL', '{"type":"SHELL","command":"python /opt/analytics/visit_stats.py"}', 'CRON', '0 3 * * *', 1800, 1, 60, 5, '{"cpu":2,"memory_mb":4096}', 0, 0, '统计用户访问数据', 2, 1),
(5, 2, '用户行为报表', 'user-behavior-report', 'PYTHON', '{"type":"PYTHON","script":"/opt/analytics/behavior_report.py"}', 'CRON', '0 8 * * 1', 3600, 0, 0, 5, '{"cpu":4,"memory_mb":8192}', 1, 0, '生成周度用户行为报表', 2, 1),

-- CV模型训练任务
(6, 3, '图像预处理', 'image-preprocess', 'PYTHON', '{"type":"PYTHON","script":"/opt/cv/preprocess.py","env":{"CUDA_VISIBLE_DEVICES":"0,1"}}', 'MANUAL', NULL, 7200, 0, 0, 7, '{"cpu":8,"memory_mb":32768,"gpu":2,"gpu_model":"V100"}', 1, 1, '图像数据预处理', 2, 1),
(7, 3, 'ResNet训练', 'resnet-training', 'DOCKER', '{"type":"DOCKER","image":"pytorch/pytorch:1.12-cuda11.3","command":"python train.py --model resnet50","volumes":["/data:/data"]}', 'MANUAL', NULL, 43200, 1, 300, 9, '{"cpu":16,"memory_mb":65536,"gpu":4,"gpu_model":"A100"}', 1, 1, 'ResNet50模型训练', 2, 1),

-- NLP实验任务
(8, 4, 'BERT微调', 'bert-finetune', 'DOCKER', '{"type":"DOCKER","image":"huggingface/transformers-pytorch-gpu:latest","command":"python finetune.py"}', 'MANUAL', NULL, 14400, 0, 0, 8, '{"cpu":8,"memory_mb":32768,"gpu":2}', 1, 1, 'BERT模型微调', 4, 1),

-- 性能测试任务
(9, 5, 'API压力测试', 'api-stress-test', 'SHELL', '{"type":"SHELL","command":"ab -n 10000 -c 100 http://api.example.com/"}', 'MANUAL', NULL, 600, 0, 0, 5, '{"cpu":4,"memory_mb":4096}', 0, 0, 'API接口压力测试', 3, 1),
(10, 5, '并发测试', 'concurrent-test', 'PYTHON', '{"type":"PYTHON","script":"/opt/test/concurrent_test.py"}', 'MANUAL', NULL, 1800, 0, 0, 5, '{"cpu":8,"memory_mb":8192}', 0, 1, '系统并发能力测试', 3, 1);

-- ============================================================================
-- 7. 任务依赖关系数据（DAG）
-- ============================================================================

INSERT INTO `task_dependency` (`parent_task_id`, `child_task_id`, `dependency_type`) VALUES
-- ETL工作流依赖：extract -> clean -> load
(1, 2, 'SUCCESS'),
(2, 3, 'SUCCESS');

-- ============================================================================
-- 8. 工作流定义数据
-- ============================================================================

INSERT INTO `workflow` (`id`, `project_id`, `workflow_name`, `workflow_code`, `description`, `dag_json`, `schedule_type`, `cron_expression`, `next_schedule_time`, `timeout_seconds`, `alert_on_failure`, `creator_user_id`, `status`) VALUES
(1, 1, '每日ETL工作流', 'daily-etl', '每日凌晨2点执行的ETL工作流', 
 '{
   "nodes": [
     {"id": "extract", "task_id": 1, "name": "数据抽取"},
     {"id": "clean", "task_id": 2, "name": "数据清洗"},
     {"id": "load", "task_id": 3, "name": "数据加载"}
   ],
   "edges": [
     {"from": "extract", "to": "clean"},
     {"from": "clean", "to": "load"}
   ]
 }', 
 'CRON', '0 2 * * *', DATE_ADD(DATE_FORMAT(NOW(), '%Y-%m-%d 02:00:00'), INTERVAL 1 DAY), 10800, 1, 1, 1),

(2, 3, 'CV模型训练流水线', 'cv-pipeline', 'CV模型完整训练流水线', 
 '{
   "nodes": [
     {"id": "preprocess", "task_id": 6, "name": "图像预处理"},
     {"id": "train", "task_id": 7, "name": "ResNet训练"}
   ],
   "edges": [
     {"from": "preprocess", "to": "train"}
   ]
 }', 
 'MANUAL', NULL, NULL, 50400, 1, 2, 1);

-- ============================================================================
-- 9. 任务实例示例数据（历史数据）
-- ============================================================================

-- 插入一些历史任务实例
INSERT INTO `task_instance` (`id`, `task_id`, `workflow_instance_id`, `instance_code`, `trigger_type`, `trigger_user_id`, `status`, `priority`, `resource_node_id`, `scheduled_time`, `start_time`, `end_time`, `duration_ms`, `exit_code`, `retry_count`) VALUES
(1, 1, NULL, 'TASK_1_1711929600_1234', 'CRON', NULL, 'SUCCESS', 8, 1, '2026-04-01 02:00:00', '2026-04-01 02:00:05', '2026-04-01 02:15:23', 918000, 0, 0),
(2, 2, NULL, 'TASK_2_1711933200_5678', 'DEPENDENCY', NULL, 'SUCCESS', 7, 1, '2026-04-01 02:15:30', '2026-04-01 02:15:35', '2026-04-01 03:05:12', 2977000, 0, 0),
(3, 3, NULL, 'TASK_3_1711936800_9012', 'DEPENDENCY', NULL, 'SUCCESS', 6, 2, '2026-04-01 03:05:20', '2026-04-01 03:05:25', '2026-04-01 03:25:45', 1220000, 0, 0),
(4, 4, NULL, 'TASK_4_1711933200_3456', 'CRON', NULL, 'SUCCESS', 5, 2, '2026-04-01 03:00:00', '2026-04-01 03:00:03', '2026-04-01 03:18:47', 1124000, 0, 0),
(5, 6, NULL, 'TASK_6_1711958400_7890', 'MANUAL', 2, 'SUCCESS', 7, 3, '2026-04-01 10:00:00', '2026-04-01 10:00:10', '2026-04-01 11:35:28', 5718000, 0, 0),
(6, 7, NULL, 'TASK_7_1711964100_2345', 'MANUAL', 2, 'RUNNING', 9, 4, '2026-04-01 11:35:35', '2026-04-01 11:35:40', NULL, NULL, NULL, 0),
(7, 9, NULL, 'TASK_9_1711972800_6789', 'MANUAL', 3, 'SUCCESS', 5, 1, '2026-04-01 14:00:00', '2026-04-01 14:00:05', '2026-04-01 14:08:32', 507000, 0, 0);

-- ============================================================================
-- 10. 工作流实例示例数据
-- ============================================================================

INSERT INTO `workflow_instance` (`id`, `workflow_id`, `instance_code`, `trigger_type`, `trigger_user_id`, `status`, `start_time`, `end_time`, `duration_ms`, `total_tasks`, `success_tasks`, `failed_tasks`) VALUES
(1, 1, 'WF_1_20260401_001', 'CRON', NULL, 'SUCCESS', '2026-04-01 02:00:00', '2026-04-01 03:25:45', 5145000, 3, 3, 0);

-- ============================================================================
-- 11. 告警规则数据
-- ============================================================================

INSERT INTO `alert_rule` (`tenant_id`, `rule_name`, `rule_type`, `target_type`, `target_id`, `condition_config`, `notification_channels`, `notification_users`, `status`) VALUES
(1, 'ETL任务失败告警', 'TASK_FAILURE', 'PROJECT', 1, '{"threshold":1,"time_window":3600}', '["EMAIL","WEBHOOK"]', '[1,2]', 1),
(2, 'GPU训练超时告警', 'TASK_TIMEOUT', 'PROJECT', 3, '{"timeout_seconds":43200}', '["EMAIL"]', '[2,4]', 1),
(1, '租户资源不足告警', 'RESOURCE_SHORTAGE', 'PROJECT', NULL, '{"cpu_threshold":0.9,"memory_threshold":0.9}', '["EMAIL","SMS"]', '[1]', 1);

-- ============================================================================
-- 12. 执行日志示例数据
-- ============================================================================

INSERT INTO `execution_log` (`task_instance_id`, `log_level`, `log_content`, `log_time`, `source`) VALUES
(1, 'INFO', '任务开始执行', '2026-04-01 02:00:05', 'SYSTEM'),
(1, 'INFO', '正在连接数据源...', '2026-04-01 02:00:10', 'STDOUT'),
(1, 'INFO', '数据抽取进度: 50%', '2026-04-01 02:07:30', 'STDOUT'),
(1, 'INFO', '数据抽取完成，共抽取 1,234,567 条记录', '2026-04-01 02:15:20', 'STDOUT'),
(1, 'INFO', '任务执行成功', '2026-04-01 02:15:23', 'SYSTEM'),
(2, 'INFO', '任务开始执行', '2026-04-01 02:15:35', 'SYSTEM'),
(2, 'INFO', '开始数据清洗...', '2026-04-01 02:15:40', 'STDOUT'),
(2, 'WARN', '发现 123 条异常数据', '2026-04-01 02:30:15', 'STDOUT'),
(2, 'INFO', '数据清洗完成', '2026-04-01 03:05:10', 'STDOUT'),
(2, 'INFO', '任务执行成功', '2026-04-01 03:05:12', 'SYSTEM'),
(6, 'INFO', '任务开始执行', '2026-04-01 11:35:40', 'SYSTEM'),
(6, 'INFO', 'GPU训练开始，使用4张A100显卡', '2026-04-01 11:35:50', 'STDOUT'),
(6, 'INFO', 'Epoch 1/100, Loss: 0.8234', '2026-04-01 11:45:20', 'STDOUT');

-- ============================================================================
-- 13. 性能指标快照示例数据
-- ============================================================================

INSERT INTO `metric_snapshot` (`resource_type`, `resource_id`, `metric_type`, `metric_value`, `snapshot_time`) VALUES
('NODE', 1, 'CPU_USAGE', 45.67, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 1, 'MEMORY_USAGE', 38.92, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 3, 'CPU_USAGE', 87.34, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 3, 'MEMORY_USAGE', 76.45, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 3, 'GPU_USAGE', 92.18, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 4, 'CPU_USAGE', 95.21, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 4, 'MEMORY_USAGE', 88.67, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('NODE', 4, 'GPU_USAGE', 98.45, DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
('TASK_INSTANCE', 6, 'CPU_USAGE', 95.21, DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
('TASK_INSTANCE', 6, 'MEMORY_USAGE', 88.67, DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
('TASK_INSTANCE', 6, 'GPU_USAGE', 98.45, DATE_SUB(NOW(), INTERVAL 2 MINUTE));

-- ============================================================================
-- 数据验证查询
-- ============================================================================

-- 统计各表数据量
SELECT 'user' AS table_name, COUNT(*) AS row_count FROM user
UNION ALL
SELECT 'tenant', COUNT(*) FROM tenant
UNION ALL
SELECT 'tenant_member', COUNT(*) FROM tenant_member
UNION ALL
SELECT 'project', COUNT(*) FROM project
UNION ALL
SELECT 'task', COUNT(*) FROM task
UNION ALL
SELECT 'task_dependency', COUNT(*) FROM task_dependency
UNION ALL
SELECT 'task_instance', COUNT(*) FROM task_instance
UNION ALL
SELECT 'workflow', COUNT(*) FROM workflow
UNION ALL
SELECT 'workflow_instance', COUNT(*) FROM workflow_instance
UNION ALL
SELECT 'resource_node', COUNT(*) FROM resource_node
UNION ALL
SELECT 'resource_quota', COUNT(*) FROM resource_quota
UNION ALL
SELECT 'alert_rule', COUNT(*) FROM alert_rule
UNION ALL
SELECT 'execution_log', COUNT(*) FROM execution_log
UNION ALL
SELECT 'metric_snapshot', COUNT(*) FROM metric_snapshot;

-- ============================================================================
-- 初始化完成
-- ============================================================================

SELECT '数据初始化完成！' AS message;
SELECT '已插入示例用户、租户、项目、任务、工作流、资源节点等数据' AS summary;
