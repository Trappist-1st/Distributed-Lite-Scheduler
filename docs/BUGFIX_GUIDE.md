# 🔧 数据库修复说明文档

## 问题描述

执行 `schema.sql` 时，视图创建会报错：
```
ERROR 1054 (42S22): Unknown column 'ti.deleted' in 'where clause'
```

## 根本原因

**设计决策**：
- `task_instance` 和 `workflow_instance` 是**历史记录表**，不应该被删除
- 这些表**没有** `deleted` 字段
- 但视图定义中错误地在 WHERE 子句中引用了 `ti.deleted`

## 受影响的表

### ✅ 有 `deleted` 字段的表（定义表）
| 表名 | 类型 | 说明 |
|------|------|------|
| `user` | 实体表 | 用户可以被禁用/软删除 |
| `tenant` | 实体表 | 租户可以被软删除 |
| `project` | 实体表 | 项目可以被软删除 |
| `task` | 定义表 | 任务定义可以被软删除 |
| `workflow` | 定义表 | 工作流定义可以被软删除 |

### ❌ 没有 `deleted` 字段的表（实例表）
| 表名 | 类型 | 说明 |
|------|------|------|
| `task_instance` | 实例表 | **历史记录，永久保留** |
| `workflow_instance` | 实例表 | **历史记录，永久保留** |
| `tenant_member` | 关系表 | 成员关系，直接删除 |
| `task_dependency` | 关系表 | 依赖关系，直接删除 |
| `resource_node` | 配置表 | 资源节点，状态控制 |
| `resource_quota` | 配置表 | 资源配额，直接更新 |
| `execution_log` | 日志表 | 执行日志，永久保留 |
| `alert_rule` | 配置表 | 告警规则，状态控制 |
| `metric_snapshot` | 快照表 | 性能快照，定期归档 |

## 修复方案

### 方案1: 使用修复补丁（推荐）⭐

```bash
# 如果已经执行过 schema.sql，使用补丁修复
mysql -uscheduler -pscheduler123 distributed_scheduler < hotfix-views.sql
```

### 方案2: 重新执行完整脚本

```bash
# 删除数据库重新开始
mysql -uroot -p -e "DROP DATABASE IF EXISTS distributed_scheduler;"
mysql -uroot -p -e "CREATE DATABASE distributed_scheduler CHARACTER SET utf8mb4;"

# 执行修复后的 schema.sql
mysql -uscheduler -pscheduler123 distributed_scheduler < schema.sql

# 导入初始化数据
mysql -uscheduler -pscheduler123 distributed_scheduler < init-data.sql
```

## 修复详情

### 1. v_task_instance_detail 视图

**❌ 错误的定义**：
```sql
FROM task_instance ti
JOIN task t ON ti.task_id = t.id
JOIN project p ON t.project_id = p.id
JOIN tenant tn ON p.tenant_id = tn.id
LEFT JOIN resource_node rn ON ti.resource_node_id = rn.id
LEFT JOIN user u ON ti.trigger_user_id = u.id
WHERE ti.deleted = 0 AND t.deleted = 0 AND p.deleted = 0;  -- ❌ ti.deleted 不存在
```

**✅ 正确的定义**：
```sql
FROM task_instance ti
JOIN task t ON ti.task_id = t.id AND t.deleted = 0          -- ✅ 在 JOIN 中过滤
JOIN project p ON t.project_id = p.id AND p.deleted = 0     -- ✅ 在 JOIN 中过滤
JOIN tenant tn ON p.tenant_id = tn.id AND tn.deleted = 0    -- ✅ 在 JOIN 中过滤
LEFT JOIN resource_node rn ON ti.resource_node_id = rn.id
LEFT JOIN user u ON ti.trigger_user_id = u.id AND u.deleted = 0;  -- ✅ 在 JOIN 中过滤
-- 不需要 WHERE 子句
```

### 2. v_task_success_rate 视图

**❌ 错误的定义**：
```sql
FROM task t
JOIN project p ON t.project_id = p.id
LEFT JOIN task_instance ti ON ...
WHERE t.deleted = 0 AND p.deleted = 0  -- ❌ p.deleted 在 WHERE 中重复检查
```

**✅ 正确的定义**：
```sql
FROM task t
JOIN project p ON t.project_id = p.id AND p.deleted = 0  -- ✅ 在 JOIN 中过滤
LEFT JOIN task_instance ti ON ...
WHERE t.deleted = 0  -- ✅ 只检查主表
```

### 3. v_workflow_stats 视图

**❌ 错误的定义**：
```sql
FROM workflow w
JOIN project p ON w.project_id = p.id
LEFT JOIN workflow_instance wi ON ...
WHERE w.deleted = 0 AND p.deleted = 0  -- ❌ p.deleted 重复检查
```

**✅ 正确的定义**：
```sql
FROM workflow w
JOIN project p ON w.project_id = p.id AND p.deleted = 0  -- ✅ 在 JOIN 中过滤
LEFT JOIN workflow_instance wi ON ...
WHERE w.deleted = 0  -- ✅ 只检查主表
```

## 验证修复

### 1. 检查视图是否创建成功

```sql
SELECT 
    TABLE_NAME,
    TABLE_COMMENT
FROM information_schema.TABLES 
WHERE TABLE_SCHEMA = 'distributed_scheduler'
  AND TABLE_TYPE = 'VIEW'
ORDER BY TABLE_NAME;
```

**预期输出**：
```
+-------------------------------+
| TABLE_NAME                    |
+-------------------------------+
| v_resource_node_utilization   |
| v_task_instance_detail        |
| v_task_success_rate           |
| v_tenant_resource_stats       |
| v_workflow_stats              |
+-------------------------------+
5 rows
```

### 2. 测试每个视图

```sql
-- 测试任务实例详情视图
SELECT * FROM v_task_instance_detail LIMIT 5;

-- 测试租户资源统计视图
SELECT * FROM v_tenant_resource_stats;

-- 测试任务成功率视图
SELECT * FROM v_task_success_rate LIMIT 5;

-- 测试资源节点利用率视图
SELECT * FROM v_resource_node_utilization;

-- 测试工作流统计视图
SELECT * FROM v_workflow_stats LIMIT 5;
```

### 3. 检查数据一致性

```sql
-- 验证视图返回的数据量
SELECT 'v_task_instance_detail' AS view_name, COUNT(*) AS row_count 
FROM v_task_instance_detail
UNION ALL
SELECT 'v_tenant_resource_stats', COUNT(*) FROM v_tenant_resource_stats
UNION ALL
SELECT 'v_task_success_rate', COUNT(*) FROM v_task_success_rate
UNION ALL
SELECT 'v_resource_node_utilization', COUNT(*) FROM v_resource_node_utilization
UNION ALL
SELECT 'v_workflow_stats', COUNT(*) FROM v_workflow_stats;
```

**预期输出**（基于 init-data.sql）：
```
+-------------------------------+-----------+
| view_name                     | row_count |
+-------------------------------+-----------+
| v_task_instance_detail        |         7 |
| v_tenant_resource_stats       |         3 |
| v_task_success_rate           |        10 |
| v_resource_node_utilization   |         5 |
| v_workflow_stats              |         2 |
+-------------------------------+-----------+
```

## 设计原则总结

### 为什么实例表不使用 deleted 字段？

1. **审计需求**：历史执行记录必须永久保留
2. **数据分析**：需要完整的历史数据做趋势分析
3. **合规要求**：某些行业要求保留完整执行日志
4. **性能考虑**：deleted=0 条件会降低查询性能

### 如何清理历史数据？

**不推荐**：软删除（deleted 字段）  
**推荐方案**：

```sql
-- 1. 数据归档（定期执行）
INSERT INTO task_instance_archive 
SELECT * FROM task_instance 
WHERE created_at < DATE_SUB(NOW(), INTERVAL 1 YEAR);

DELETE FROM task_instance 
WHERE created_at < DATE_SUB(NOW(), INTERVAL 1 YEAR);

-- 2. 分区表自动清理（推荐）
ALTER TABLE task_instance 
PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p2025 VALUES LESS THAN (TO_DAYS('2026-01-01')),
    PARTITION p2026 VALUES LESS THAN (TO_DAYS('2027-01-01'))
);

-- 删除旧分区
ALTER TABLE task_instance DROP PARTITION p2024;
```

## 其他潜在问题检查

### 1. 存储过程中的 deleted 引用 ✅

检查 `sp_submit_task_instance`：
```sql
-- ✅ 正确：只检查 task 表的 deleted
WHERE t.id = p_task_id AND t.deleted = 0
```

### 2. 触发器中的 deleted 引用 ✅

检查 `trg_project_delete_cascade`：
```sql
-- ✅ 正确：更新 task 和 workflow 的 deleted 字段
UPDATE task SET deleted = 1 WHERE project_id = NEW.id;
UPDATE workflow SET deleted = 1 WHERE project_id = NEW.id;
```

### 3. 复杂查询中的 deleted 引用 ✅

所有复杂查询示例都已更新，不引用实例表的 deleted 字段。

## 文件清单

修复涉及的文件：

| 文件名 | 状态 | 说明 |
|--------|------|------|
| `schema.sql` | ✅ 已修复 | 主DDL脚本，视图定义已更新 |
| `hotfix-views.sql` | ✅ 新增 | 独立的修复补丁脚本 |
| `DATABASE_DESIGN.md` | ⚠️ 待更新 | 设计文档需同步更新 |
| `init-data.sql` | ✅ 无需修改 | 数据脚本不受影响 |

## 常见问题 FAQ

### Q1: 为什么不给所有表都加 deleted 字段？
**A**: 不同类型的表有不同的生命周期管理策略：
- **定义表**（task, workflow）：可修改，需要软删除
- **实例表**（task_instance）：不可变历史记录，永久保留
- **关系表**（tenant_member）：直接删除即可
- **配置表**（resource_node）：通过 status 字段控制

### Q2: 如果我已经执行了错误的 schema.sql 怎么办？
**A**: 执行 `hotfix-views.sql` 补丁脚本即可，会删除旧视图并重建。

### Q3: 这个问题会影响数据安全吗？
**A**: 不会。这只是视图定义问题，不影响表结构和数据。

### Q4: 未来如何避免这类问题？
**A**: 
1. 在设计阶段明确哪些表需要 deleted 字段
2. 编写 DDL 后立即测试执行
3. 使用 Liquibase/Flyway 等工具管理数据库版本

---

## 修复完成确认

完成以下检查，确保修复成功：

- [ ] 执行 `hotfix-views.sql` 或更新后的 `schema.sql`
- [ ] 5个视图全部创建成功
- [ ] 每个视图都可以正常查询
- [ ] 查询结果符合预期
- [ ] 无报错信息

✅ **修复完成！现在可以正常使用数据库了。**

---

**版本**: v1.0.1  
**更新日期**: 2026-04-02  
**作者**: Database Team
