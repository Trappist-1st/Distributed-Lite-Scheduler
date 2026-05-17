-- P4-4 可选：为工作流实例增加上下文列，供 SpEL 变量 context.* 持久化引用。
-- 执行前请在目标环境备份；字段名与实体 WorkflowInstance.contextJson 对齐后再将 @TableField(exist = false) 移除。
--
-- AFTER 子句仅用于约定列顺序；必须与当前表实际存在的列名一致。
-- resources/sql/workflow_instance.sql 基线表无 execution_plan；若库表尚未补充该列，不要用 AFTER execution_plan。

-- 基线表（见 workflow_instance.sql）：放在 duration_ms 之后、审计字段之前
-- ALTER TABLE workflow_instance
--   ADD COLUMN context_json JSON NULL COMMENT 'P4-4 实例级上下文(JSON)' AFTER duration_ms;

-- 若库表已与 WorkflowInstance 对齐且存在 execution_plan，可插在其后：
-- ALTER TABLE workflow_instance
--   ADD COLUMN context_json JSON NULL COMMENT 'P4-4 实例级上下文(JSON)' AFTER execution_plan;

-- 不关心列顺序时可省略 AFTER（新列落在表末尾）：
-- ALTER TABLE workflow_instance
--   ADD COLUMN context_json JSON NULL COMMENT 'P4-4 实例级上下文(JSON)';
