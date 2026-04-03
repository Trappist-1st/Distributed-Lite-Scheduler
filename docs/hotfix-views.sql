-- ============================================================================
-- 数据库修复补丁 (Hotfix Patch)
-- 修复视图中的 deleted 字段引用错误
-- 版本: v1.0.1
-- 日期: 2026-04-02
-- ============================================================================

-- 问题说明：
-- task_instance 和 workflow_instance 表没有 deleted 字段（历史记录表不应删除）
-- 但视图中错误地在 WHERE 子句中引用了这些字段
-- 
-- 解决方案：
-- 将 deleted 检查移到 JOIN 条件中，只检查定义表（task, project, tenant 等）

-- ============================================================================
-- 1. 删除旧视图
-- ============================================================================

DROP VIEW IF EXISTS v_task_instance_detail;
DROP VIEW IF EXISTS v_tenant_resource_stats;
DROP VIEW IF EXISTS v_task_success_rate;
DROP VIEW IF EXISTS v_resource_node_utilization;
DROP VIEW IF EXISTS v_workflow_stats;

-- ============================================================================
-- 2. 重新创建修复后的视图
-- ============================================================================

-- 2.1 任务实例详情视图 (修复: 移除 ti.deleted)
CREATE VIEW v_task_instance_detail AS
SELECT 
    ti.id AS instance_id,
    ti.instance_code,
    ti.status AS instance_status,
    ti.start_time,
    ti.end_time,
    ti.duration_ms,
    t.id AS task_id,
    t.task_name,
    t.task_type,
    p.id AS project_id,
    p.project_name,
    tn.id AS tenant_id,
    tn.tenant_name,
    rn.id AS node_id,
    rn.node_name,
    rn.node_host,
    u.id AS trigger_user_id,
    u.username AS trigger_username
FROM task_instance ti
JOIN task t ON ti.task_id = t.id AND t.deleted = 0
JOIN project p ON t.project_id = p.id AND p.deleted = 0
JOIN tenant tn ON p.tenant_id = tn.id AND tn.deleted = 0
LEFT JOIN resource_node rn ON ti.resource_node_id = rn.id
LEFT JOIN user u ON ti.trigger_user_id = u.id AND u.deleted = 0;

-- 2.2 租户资源使用统计视图 (无需修改，但为完整性重建)
CREATE VIEW v_tenant_resource_stats AS
SELECT 
    tn.id AS tenant_id,
    tn.tenant_name,
    rq.max_cpu,
    rq.max_memory_mb,
    rq.max_gpu,
    rq.used_cpu,
    rq.used_memory_mb,
    rq.used_gpu,
    ROUND(rq.used_cpu * 100.0 / NULLIF(rq.max_cpu, 0), 2) AS cpu_usage_percent,
    ROUND(rq.used_memory_mb * 100.0 / NULLIF(rq.max_memory_mb, 0), 2) AS memory_usage_percent,
    ROUND(rq.used_gpu * 100.0 / NULLIF(rq.max_gpu, 0), 2) AS gpu_usage_percent,
    rq.running_tasks,
    rq.max_running_tasks
FROM tenant tn
JOIN resource_quota rq ON tn.id = rq.tenant_id
WHERE tn.deleted = 0;

-- 2.3 任务成功率统计视图 (修复: 移除 p.deleted 重复检查)
CREATE VIEW v_task_success_rate AS
SELECT 
    t.id AS task_id,
    t.task_name,
    p.project_name,
    COUNT(ti.id) AS total_runs,
    SUM(CASE WHEN ti.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_runs,
    SUM(CASE WHEN ti.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_runs,
    ROUND(SUM(CASE WHEN ti.status = 'SUCCESS' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(ti.id), 0), 2) AS success_rate,
    AVG(ti.duration_ms) / 1000 AS avg_duration_seconds,
    MAX(ti.end_time) AS last_run_time
FROM task t
JOIN project p ON t.project_id = p.id AND p.deleted = 0
LEFT JOIN task_instance ti ON t.id = ti.task_id AND ti.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
WHERE t.deleted = 0
GROUP BY t.id, t.task_name, p.project_name;

-- 2.4 资源节点利用率视图 (无需修改，resource_node 没有 deleted 字段)
CREATE VIEW v_resource_node_utilization AS
SELECT 
    rn.id AS node_id,
    rn.node_name,
    rn.node_host,
    rn.node_type,
    rn.total_cpu,
    rn.available_cpu,
    rn.total_cpu - rn.available_cpu AS used_cpu,
    ROUND((rn.total_cpu - rn.available_cpu) * 100.0 / NULLIF(rn.total_cpu, 0), 2) AS cpu_utilization,
    rn.total_memory_mb,
    rn.available_memory_mb,
    ROUND((rn.total_memory_mb - rn.available_memory_mb) * 100.0 / NULLIF(rn.total_memory_mb, 0), 2) AS memory_utilization,
    rn.total_gpu,
    rn.available_gpu,
    rn.total_gpu - rn.available_gpu AS used_gpu,
    rn.status,
    rn.last_heartbeat_time,
    TIMESTAMPDIFF(SECOND, rn.last_heartbeat_time, NOW()) AS heartbeat_age_seconds
FROM resource_node rn;

-- 2.5 工作流执行统计视图 (修复: 移除 p.deleted 重复检查)
CREATE VIEW v_workflow_stats AS
SELECT 
    w.id AS workflow_id,
    w.workflow_name,
    p.project_name,
    COUNT(wi.id) AS total_runs,
    SUM(CASE WHEN wi.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_runs,
    SUM(CASE WHEN wi.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_runs,
    ROUND(SUM(CASE WHEN wi.status = 'SUCCESS' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(wi.id), 0), 2) AS success_rate,
    AVG(wi.duration_ms) / 1000 AS avg_duration_seconds,
    MAX(wi.end_time) AS last_run_time
FROM workflow w
JOIN project p ON w.project_id = p.id AND p.deleted = 0
LEFT JOIN workflow_instance wi ON w.id = wi.workflow_id AND wi.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
WHERE w.deleted = 0
GROUP BY w.id, w.workflow_name, p.project_name;

-- ============================================================================
-- 3. 验证修复
-- ============================================================================

-- 查询所有视图，确保创建成功
SELECT 
    TABLE_NAME,
    TABLE_COMMENT
FROM information_schema.TABLES 
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'VIEW'
ORDER BY TABLE_NAME;

-- 测试每个视图是否可查询
SELECT 'v_task_instance_detail' AS view_name, COUNT(*) AS row_count FROM v_task_instance_detail
UNION ALL
SELECT 'v_tenant_resource_stats', COUNT(*) FROM v_tenant_resource_stats
UNION ALL
SELECT 'v_task_success_rate', COUNT(*) FROM v_task_success_rate
UNION ALL
SELECT 'v_resource_node_utilization', COUNT(*) FROM v_resource_node_utilization
UNION ALL
SELECT 'v_workflow_stats', COUNT(*) FROM v_workflow_stats;

-- ============================================================================
-- 修复完成！
-- ============================================================================

SELECT '视图修复完成！所有 deleted 字段引用错误已修复。' AS message;

-- ============================================================================
-- 附录：哪些表有 deleted 字段
-- ============================================================================

/*
有 deleted 字段的表（定义表）：
1. user
2. tenant
3. project
4. task
5. workflow

没有 deleted 字段的表（实例表/配置表）：
1. tenant_member - 成员关系表
2. task_instance - 任务实例（历史记录）
3. task_dependency - 依赖关系
4. workflow_instance - 工作流实例（历史记录）
5. resource_node - 资源节点
6. resource_quota - 资源配额
7. execution_log - 执行日志
8. alert_rule - 告警规则
9. metric_snapshot - 性能快照

设计原则：
- 定义表（可修改的配置）使用软删除（deleted字段）
- 实例表（不可变的历史记录）永久保留，不使用 deleted
- 关系表和配置表直接删除或保持激活状态
*/
