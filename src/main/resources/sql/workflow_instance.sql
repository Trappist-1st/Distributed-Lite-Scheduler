-- =====================================================
-- 工作流实例相关表结构
-- =====================================================

-- 1. 工作流实例表
-- 注意：根据现有WorkflowInstance实体，表结构可能已存在
-- 如果表结构与设计稿不一致，请以现有结构为准

CREATE TABLE IF NOT EXISTS `workflow_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '工作流实例ID',
    `workflow_id` BIGINT NOT NULL COMMENT '工作流定义ID',
    `instance_code` VARCHAR(100) COMMENT '实例唯一标识',
    
    -- 触发信息
    `trigger_type` VARCHAR(20) DEFAULT 'MANUAL' COMMENT '触发类型：MANUAL/SCHEDULED/API',
    `trigger_user_id` BIGINT COMMENT '触发用户ID',
    
    -- 执行状态
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '执行状态：PENDING/PREPARING/RUNNING/PAUSED/SUCCESS/FAILED/PARTIAL_SUCCESS/CANCELLED',
    
    -- 执行进度
    `total_tasks` INT NOT NULL DEFAULT 0 COMMENT '总任务数',
    `success_tasks` INT DEFAULT 0 COMMENT '成功任务数',
    `failed_tasks` INT DEFAULT 0 COMMENT '失败任务数',
    
    -- 时间信息
    `start_time` DATETIME COMMENT '开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `duration_ms` BIGINT COMMENT '执行时长（毫秒）',
    
    -- 审计字段
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    PRIMARY KEY (`id`),
    INDEX `idx_workflow_id` (`workflow_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_start_time` (`start_time` DESC),
    INDEX `idx_trigger_user_id` (`trigger_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流实例表';


-- 2. 工作流任务实例表
CREATE TABLE IF NOT EXISTS `workflow_task_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务实例ID',
    `workflow_instance_id` BIGINT NOT NULL COMMENT '工作流实例ID',
    `task_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    
    -- 任务定义（快照）
    `task_definition` JSON NOT NULL COMMENT '任务定义(JSON快照)',
    
    -- 执行状态
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '执行状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    
    -- 执行信息
    `layer_index` INT NOT NULL COMMENT '所属层级索引',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `task_instance_id` BIGINT COMMENT '关联的TaskInstance ID',
    
    -- 时间信息
    `start_time` DATETIME COMMENT '开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `duration_seconds` INT COMMENT '执行时长(秒)',
    
    -- 结果信息
    `exit_code` INT COMMENT '退出码',
    `output` TEXT COMMENT '输出信息',
    `error_message` TEXT COMMENT '错误信息',
    
    -- 审计字段
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    PRIMARY KEY (`id`),
    INDEX `idx_workflow_instance` (`workflow_instance_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_layer_index` (`layer_index`),
    INDEX `idx_task_instance` (`task_instance_id`),
    UNIQUE KEY `uk_instance_task` (`workflow_instance_id`, `task_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流任务实例表';


-- =====================================================
-- 示例数据（可选）
-- =====================================================

-- 插入示例工作流实例
-- INSERT INTO workflow_instance (workflow_id, instance_code, trigger_type, trigger_user_id, status, total_tasks)
-- VALUES (1, 'ETL_WORKFLOW_20260514_180000', 'MANUAL', 1, 'PENDING', 5);

-- 插入示例任务实例
-- INSERT INTO workflow_task_instance (workflow_instance_id, task_name, task_definition, status, layer_index)
-- VALUES (1, 'extract_data', '{"taskName":"extract_data","command":"python extract.py"}', 'PENDING', 0);


-- =====================================================
-- 数据库索引优化建议
-- =====================================================

-- 1. workflow_instance 表
-- 已创建索引：
--   - idx_workflow_id: 用于按工作流ID查询实例列表
--   - idx_status: 用于按状态查询
--   - idx_start_time: 用于按时间排序
--   - idx_trigger_user_id: 用于按用户查询

-- 2. workflow_task_instance 表
-- 已创建索引：
--   - idx_workflow_instance: 用于查询某个工作流实例的所有任务
--   - idx_status: 用于按状态查询
--   - idx_layer_index: 用于按层级查询
--   - uk_instance_task: 唯一约束，确保同一实例中任务名称唯一

-- 如果需要按工作流实例ID和层级联合查询，可以考虑添加联合索引：
-- CREATE INDEX idx_instance_layer ON workflow_task_instance(workflow_instance_id, layer_index);

-- 如果需要查询运行中的任务，可以考虑添加联合索引：
-- CREATE INDEX idx_instance_status ON workflow_task_instance(workflow_instance_id, status);
