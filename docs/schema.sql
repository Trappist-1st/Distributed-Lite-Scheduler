-- ============================================================================
-- 分布式轻量级调度系统 - 数据库初始化脚本
-- Database Schema for Distributed Lite Scheduler
-- 版本: v1.0
-- 创建日期: 2026-04-02
-- 数据库: MySQL 8.0+ / PostgreSQL 14+
-- ============================================================================

-- 设置字符集和时区
SET NAMES utf8mb4;
SET TIME_ZONE = '+08:00';

-- ============================================================================
-- 1. 用户模块 (User Module)
-- ============================================================================

-- 1.1 用户表
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希（bcrypt）',
    `nickname` VARCHAR(50) COMMENT '昵称',
    `avatar_url` VARCHAR(255) COMMENT '头像URL',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `last_login_time` DATETIME COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) COMMENT '最后登录IP',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 1.2 租户表
DROP TABLE IF EXISTS `tenant`;
CREATE TABLE `tenant` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    `tenant_name` VARCHAR(100) NOT NULL COMMENT '租户名称',
    `tenant_code` VARCHAR(50) NOT NULL COMMENT '租户编码（唯一标识）',
    `owner_user_id` BIGINT UNSIGNED NOT NULL COMMENT '所有者用户ID',
    `description` TEXT COMMENT '租户描述',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `expire_time` DATETIME COMMENT '过期时间（NULL表示永久）',
    `max_projects` INT NOT NULL DEFAULT 10 COMMENT '最大项目数限制',
    `max_tasks` INT NOT NULL DEFAULT 1000 COMMENT '最大任务数限制',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_code`),
    KEY `idx_owner` (`owner_user_id`),
    KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- 1.3 租户成员表
DROP TABLE IF EXISTS `tenant_member`;
CREATE TABLE `tenant_member` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '成员ID',
    `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户ID',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `role` VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT '角色：OWNER/ADMIN/MEMBER/GUEST',
    `join_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_user` (`tenant_id`, `user_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户成员表';

-- ============================================================================
-- 2. 任务模块 (Task Module)
-- ============================================================================

-- 2.1 项目表
DROP TABLE IF EXISTS `project`;
CREATE TABLE `project` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '项目ID',
    `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属租户ID',
    `project_name` VARCHAR(100) NOT NULL COMMENT '项目名称',
    `project_code` VARCHAR(50) NOT NULL COMMENT '项目编码',
    `description` TEXT COMMENT '项目描述',
    `creator_user_id` BIGINT UNSIGNED NOT NULL COMMENT '创建者用户ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `extra_config` JSON COMMENT '扩展配置（JSON）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_id`, `project_code`),
    KEY `idx_creator` (`creator_user_id`),
    KEY `idx_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- 2.2 任务定义表
DROP TABLE IF EXISTS `task`;
CREATE TABLE `task` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `project_id` BIGINT UNSIGNED NOT NULL COMMENT '所属项目ID',
    `task_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `task_code` VARCHAR(50) NOT NULL COMMENT '任务编码',
    `task_type` VARCHAR(20) NOT NULL COMMENT '任务类型：SHELL/PYTHON/DOCKER/K8S_JOB',
    `executor_config` JSON NOT NULL COMMENT '执行器配置（命令、脚本、镜像等）',
    `schedule_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT '调度类型：MANUAL/CRON/DEPENDENCY',
    `cron_expression` VARCHAR(100) COMMENT 'Cron表达式（调度类型为CRON时必填）',
    `timeout_seconds` INT NOT NULL DEFAULT 3600 COMMENT '超时时间（秒）',
    `retry_times` INT NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `retry_interval` INT NOT NULL DEFAULT 60 COMMENT '重试间隔（秒）',
    `priority` INT NOT NULL DEFAULT 5 COMMENT '优先级：1-10，数字越大优先级越高',
    `resource_require` JSON COMMENT '资源需求（CPU、内存、GPU）',
    `alert_on_failure` TINYINT NOT NULL DEFAULT 0 COMMENT '失败时告警：0-否，1-是',
    `alert_on_timeout` TINYINT NOT NULL DEFAULT 0 COMMENT '超时时告警：0-否，1-是',
    `description` TEXT COMMENT '任务描述',
    `creator_user_id` BIGINT UNSIGNED NOT NULL COMMENT '创建者用户ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_code` (`project_id`, `task_code`),
    KEY `idx_schedule_type` (`schedule_type`, `status`),
    KEY `idx_priority` (`priority`),
    KEY `idx_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义表';

-- 2.3 任务实例表
DROP TABLE IF EXISTS `task_instance`;
CREATE TABLE `task_instance` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务实例ID',
    `task_id` BIGINT UNSIGNED NOT NULL COMMENT '任务定义ID',
    `workflow_instance_id` BIGINT UNSIGNED COMMENT '所属工作流实例ID（NULL表示独立任务）',
    `instance_code` VARCHAR(100) NOT NULL COMMENT '实例唯一标识（用于幂等性）',
    `trigger_type` VARCHAR(20) NOT NULL COMMENT '触发类型：MANUAL/CRON/DEPENDENCY/API',
    `trigger_user_id` BIGINT UNSIGNED COMMENT '触发用户ID（手动触发时）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT',
    `priority` INT NOT NULL DEFAULT 5 COMMENT '优先级（继承自任务定义）',
    `resource_node_id` BIGINT UNSIGNED COMMENT '分配的资源节点ID',
    `scheduled_time` DATETIME COMMENT '预定调度时间',
    `start_time` DATETIME COMMENT '实际开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `duration_ms` BIGINT COMMENT '执行时长（毫秒）',
    `exit_code` INT COMMENT '退出码',
    `error_message` TEXT COMMENT '错误信息',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_code` (`instance_code`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_workflow_instance` (`workflow_instance_id`),
    KEY `idx_status_priority` (`status`, `priority`, `created_at`),
    KEY `idx_resource_node` (`resource_node_id`),
    KEY `idx_scheduled_time` (`scheduled_time`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务实例表';

-- 2.4 任务依赖表
DROP TABLE IF EXISTS `task_dependency`;
CREATE TABLE `task_dependency` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '依赖ID',
    `parent_task_id` BIGINT UNSIGNED NOT NULL COMMENT '父任务ID（被依赖的任务）',
    `child_task_id` BIGINT UNSIGNED NOT NULL COMMENT '子任务ID（依赖其他任务的任务）',
    `dependency_type` VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '依赖类型：SUCCESS/FAILED/FINISHED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parent_child` (`parent_task_id`, `child_task_id`),
    KEY `idx_child_task` (`child_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖表（DAG关系）';

-- ============================================================================
-- 3. 工作流模块 (Workflow Module)
-- ============================================================================

-- 3.1 工作流定义表
DROP TABLE IF EXISTS `workflow`;
CREATE TABLE `workflow` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工作流ID',
    `project_id` BIGINT UNSIGNED NOT NULL COMMENT '所属项目ID',
    `workflow_name` VARCHAR(100) NOT NULL COMMENT '工作流名称',
    `workflow_code` VARCHAR(50) NOT NULL COMMENT '工作流编码',
    `description` TEXT COMMENT '工作流描述',
    `dag_json` JSON NOT NULL COMMENT 'DAG定义（JSON格式）',
    `schedule_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT '调度类型：MANUAL/CRON',
    `cron_expression` VARCHAR(100) COMMENT 'Cron表达式',
    `next_schedule_time` DATETIME COMMENT '下次调度时间',
    `timeout_seconds` INT NOT NULL DEFAULT 7200 COMMENT '工作流超时时间（秒）',
    `alert_on_failure` TINYINT NOT NULL DEFAULT 0 COMMENT '失败时告警',
    `creator_user_id` BIGINT UNSIGNED NOT NULL COMMENT '创建者用户ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_code` (`project_id`, `workflow_code`),
    KEY `idx_schedule` (`status`, `next_schedule_time`),
    KEY `idx_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义表';

-- 3.2 工作流实例表
DROP TABLE IF EXISTS `workflow_instance`;
CREATE TABLE `workflow_instance` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工作流实例ID',
    `workflow_id` BIGINT UNSIGNED NOT NULL COMMENT '工作流定义ID',
    `instance_code` VARCHAR(100) NOT NULL COMMENT '实例唯一标识',
    `trigger_type` VARCHAR(20) NOT NULL COMMENT '触发类型：MANUAL/CRON/API',
    `trigger_user_id` BIGINT UNSIGNED COMMENT '触发用户ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCESS/FAILED/CANCELLED',
    `start_time` DATETIME COMMENT '开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `duration_ms` BIGINT COMMENT '执行时长（毫秒）',
    `total_tasks` INT NOT NULL DEFAULT 0 COMMENT '总任务数',
    `success_tasks` INT NOT NULL DEFAULT 0 COMMENT '成功任务数',
    `failed_tasks` INT NOT NULL DEFAULT 0 COMMENT '失败任务数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_code` (`instance_code`),
    KEY `idx_workflow_id` (`workflow_id`),
    KEY `idx_status` (`status`, `created_at`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流实例表';

-- ============================================================================
-- 4. 资源模块 (Resource Module)
-- ============================================================================

-- 4.1 资源节点表
DROP TABLE IF EXISTS `resource_node`;
CREATE TABLE `resource_node` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资源节点ID',
    `node_name` VARCHAR(100) NOT NULL COMMENT '节点名称',
    `node_host` VARCHAR(100) NOT NULL COMMENT '节点主机地址',
    `node_port` INT NOT NULL COMMENT '节点端口',
    `node_type` VARCHAR(20) NOT NULL COMMENT '节点类型：CPU/GPU/MIXED',
    `total_cpu` INT NOT NULL COMMENT '总CPU核心数',
    `total_memory_mb` INT NOT NULL COMMENT '总内存（MB）',
    `total_gpu` INT NOT NULL DEFAULT 0 COMMENT '总GPU卡数',
    `gpu_model` VARCHAR(50) COMMENT 'GPU型号（如：V100/A100）',
    `available_cpu` INT NOT NULL COMMENT '可用CPU核心数',
    `available_memory_mb` INT NOT NULL COMMENT '可用内存（MB）',
    `available_gpu` INT NOT NULL DEFAULT 0 COMMENT '可用GPU卡数',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ONLINE' COMMENT '状态：ONLINE/OFFLINE/MAINTENANCE',
    `last_heartbeat_time` DATETIME COMMENT '最后心跳时间',
    `labels` JSON COMMENT '标签（用于任务匹配）',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_host_port` (`node_host`, `node_port`),
    KEY `idx_status_heartbeat` (`status`, `last_heartbeat_time`),
    KEY `idx_available_resource` (`available_cpu`, `available_gpu`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源节点表';

-- 4.1a 资源槽位表（节点维度 CPU/GPU/MEMORY 快照，P2-2）
DROP TABLE IF EXISTS `resource_slot`;
CREATE TABLE `resource_slot` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '槽位行ID',
    `node_id` BIGINT UNSIGNED NOT NULL COMMENT '资源节点ID',
    `resource_type` VARCHAR(20) NOT NULL COMMENT '资源类型：CPU/GPU/MEMORY',
    `total` INT NOT NULL COMMENT '该维度总量（与节点声明对齐）',
    `available` INT NOT NULL COMMENT '可立即分配量',
    `reserved_qty` INT NOT NULL DEFAULT 0 COMMENT '已预留未释放量',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁/审计）',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_resource_type` (`node_id`, `resource_type`),
    KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源槽位表';

-- 4.1b 资源使用流水（预留/释放审计，P2-2）
DROP TABLE IF EXISTS `resource_usage`;
CREATE TABLE `resource_usage` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户ID',
    `task_instance_id` BIGINT UNSIGNED NOT NULL COMMENT '任务实例ID',
    `node_id` BIGINT UNSIGNED NOT NULL COMMENT '分配到的资源节点ID',
    `cpu_used` INT NOT NULL DEFAULT 0 COMMENT '占用 CPU 核数',
    `memory_mb_used` INT NOT NULL DEFAULT 0 COMMENT '占用内存 MB',
    `gpu_used` INT NOT NULL DEFAULT 0 COMMENT '占用 GPU 数',
    `status` VARCHAR(32) NOT NULL COMMENT '状态：RESERVED/RUNNING/RELEASED/FAILED',
    `reason` VARCHAR(255) DEFAULT NULL COMMENT '释放原因等（可选）',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `released_at` DATETIME(3) DEFAULT NULL COMMENT '释放时间',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_task_instance` (`task_instance_id`),
    KEY `idx_node_status` (`node_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源使用流水表';

-- 4.2 租户资源配额表
DROP TABLE IF EXISTS `resource_quota`;
CREATE TABLE `resource_quota` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '配额ID',
    `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户ID',
    `max_cpu` INT NOT NULL DEFAULT 10 COMMENT '最大CPU核心数',
    `max_memory_mb` INT NOT NULL DEFAULT 10240 COMMENT '最大内存（MB）',
    `max_gpu` INT NOT NULL DEFAULT 0 COMMENT '最大GPU卡数',
    `max_running_tasks` INT NOT NULL DEFAULT 50 COMMENT '最大并发运行任务数',
    `max_pending_tasks` INT NOT NULL DEFAULT 500 COMMENT '最大排队任务数',
    `used_cpu` INT NOT NULL DEFAULT 0 COMMENT '已使用CPU核心数',
    `used_memory_mb` INT NOT NULL DEFAULT 0 COMMENT '已使用内存（MB）',
    `used_gpu` INT NOT NULL DEFAULT 0 COMMENT '已使用GPU卡数',
    `running_tasks` INT NOT NULL DEFAULT 0 COMMENT '正在运行的任务数',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户资源配额表';

-- ============================================================================
-- 5. 监控模块 (Monitoring Module)
-- ============================================================================

-- 5.1 执行日志表
DROP TABLE IF EXISTS `execution_log`;
CREATE TABLE `execution_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `task_instance_id` BIGINT UNSIGNED NOT NULL COMMENT '任务实例ID',
    `log_level` VARCHAR(10) NOT NULL COMMENT '日志级别：DEBUG/INFO/WARN/ERROR',
    `log_content` TEXT NOT NULL COMMENT '日志内容',
    `log_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志时间',
    `source` VARCHAR(20) NOT NULL COMMENT '日志来源：STDOUT/STDERR/SYSTEM',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_instance` (`task_instance_id`, `log_time`),
    KEY `idx_log_time` (`log_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志表';

-- 5.1.1 任务状态流转审计表
DROP TABLE IF EXISTS `task_status_change_log`;
CREATE TABLE `task_status_change_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计ID',
    `task_instance_id` BIGINT UNSIGNED NOT NULL COMMENT '任务实例ID',
    `from_status` VARCHAR(20) NOT NULL COMMENT '原状态',
    `to_status` VARCHAR(20) NOT NULL COMMENT '目标状态',
    `trigger_source` VARCHAR(20) NOT NULL COMMENT '触发来源：SCHEDULER/WORKER/SYSTEM/API',
    `reason` VARCHAR(500) NULL COMMENT '状态变更原因',
    `operator_user_id` BIGINT UNSIGNED NULL COMMENT '操作人ID（系统触发可为空）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_instance_created` (`task_instance_id`, `created_at`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态流转审计表';

-- 5.2 告警规则表
DROP TABLE IF EXISTS `alert_rule`;
CREATE TABLE `alert_rule` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '告警规则ID',
    `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户ID',
    `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `rule_type` VARCHAR(20) NOT NULL COMMENT '规则类型：TASK_FAILURE/TASK_TIMEOUT/RESOURCE_SHORTAGE',
    `target_type` VARCHAR(20) NOT NULL COMMENT '目标类型：PROJECT/TASK/WORKFLOW',
    `target_id` BIGINT UNSIGNED COMMENT '目标ID（NULL表示全局）',
    `condition_config` JSON NOT NULL COMMENT '条件配置',
    `notification_channels` JSON NOT NULL COMMENT '通知渠道（EMAIL/SMS/WEBHOOK）',
    `notification_users` JSON COMMENT '通知用户ID列表',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则表';

-- 5.3 性能指标快照表
DROP TABLE IF EXISTS `metric_snapshot`;
CREATE TABLE `metric_snapshot` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '快照ID',
    `resource_type` VARCHAR(20) NOT NULL COMMENT '资源类型：NODE/TASK_INSTANCE',
    `resource_id` BIGINT UNSIGNED NOT NULL COMMENT '资源ID',
    `metric_type` VARCHAR(50) NOT NULL COMMENT '指标类型：CPU_USAGE/MEMORY_USAGE/GPU_USAGE',
    `metric_value` DECIMAL(10, 2) NOT NULL COMMENT '指标值',
    `snapshot_time` DATETIME NOT NULL COMMENT '快照时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_resource` (`resource_type`, `resource_id`, `snapshot_time`),
    KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='性能指标快照表';

-- ============================================================================
-- 6. 视图 (Views)
-- ============================================================================

-- 6.1 任务实例详情视图
CREATE OR REPLACE VIEW v_task_instance_detail AS
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

-- 6.2 租户资源使用统计视图
CREATE OR REPLACE VIEW v_tenant_resource_stats AS
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

-- 6.3 任务成功率统计视图
CREATE OR REPLACE VIEW v_task_success_rate AS
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

-- 6.4 资源节点利用率视图
CREATE OR REPLACE VIEW v_resource_node_utilization AS
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

-- 6.5 工作流执行统计视图
CREATE OR REPLACE VIEW v_workflow_stats AS
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
-- 7. 存储过程 (Stored Procedures)
-- ============================================================================

-- 7.1 任务提交存储过程
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_submit_task_instance$$
CREATE PROCEDURE sp_submit_task_instance(
    IN p_task_id BIGINT,
    IN p_trigger_type VARCHAR(20),
    IN p_trigger_user_id BIGINT,
    IN p_priority INT,
    OUT p_instance_id BIGINT,
    OUT p_error_code INT,
    OUT p_error_msg VARCHAR(255)
)
BEGIN
    DECLARE v_tenant_id BIGINT;
    DECLARE v_max_pending INT;
    DECLARE v_current_pending INT;
    DECLARE v_instance_code VARCHAR(100);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_error_code = 500;
        SET p_error_msg = '任务提交失败';
    END;
    
    START TRANSACTION;
    
    -- 1. 获取租户ID和配额限制
    SELECT tn.id, rq.max_pending_tasks INTO v_tenant_id, v_max_pending
    FROM task t
    JOIN project p ON t.project_id = p.id
    JOIN tenant tn ON p.tenant_id = tn.id
    JOIN resource_quota rq ON tn.id = rq.tenant_id
    WHERE t.id = p_task_id AND t.deleted = 0
    FOR UPDATE;
    
    -- 2. 检查是否超过排队任务数限制
    SELECT COUNT(*) INTO v_current_pending
    FROM task_instance ti
    JOIN task t ON ti.task_id = t.id
    JOIN project p ON t.project_id = p.id
    WHERE p.tenant_id = v_tenant_id AND ti.status = 'PENDING';
    
    IF v_current_pending >= v_max_pending THEN
        SET p_error_code = 429;
        SET p_error_msg = CONCAT('超过最大排队任务数限制: ', v_max_pending);
        ROLLBACK;
    ELSE
        -- 3. 生成实例唯一标识
        SET v_instance_code = CONCAT('TASK_', p_task_id, '_', UNIX_TIMESTAMP(), '_', FLOOR(RAND() * 10000));
        
        -- 4. 插入任务实例
        INSERT INTO task_instance (
            task_id, instance_code, trigger_type, trigger_user_id,
            status, priority, scheduled_time
        ) VALUES (
            p_task_id, v_instance_code, p_trigger_type, p_trigger_user_id,
            'PENDING', p_priority, NOW()
        );
        
        SET p_instance_id = LAST_INSERT_ID();
        SET p_error_code = 0;
        SET p_error_msg = 'success';
        
        COMMIT;
    END IF;
END$$

DELIMITER ;

-- 7.2 资源分配存储过程
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_allocate_resource$$
CREATE PROCEDURE sp_allocate_resource(
    IN p_task_instance_id BIGINT,
    IN p_required_cpu INT,
    IN p_required_memory_mb INT,
    IN p_required_gpu INT,
    OUT p_node_id BIGINT,
    OUT p_error_code INT,
    OUT p_error_msg VARCHAR(255)
)
BEGIN
    DECLARE v_tenant_id BIGINT;
    DECLARE v_current_version INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_error_code = 500;
        SET p_error_msg = '资源分配失败';
    END;
    
    START TRANSACTION;
    
    -- 1. 查找满足条件的资源节点（Best Fit算法）
    SELECT rn.id, rn.version INTO p_node_id, v_current_version
    FROM resource_node rn
    WHERE rn.status = 'ONLINE'
      AND rn.available_cpu >= p_required_cpu
      AND rn.available_memory_mb >= p_required_memory_mb
      AND rn.available_gpu >= p_required_gpu
      AND TIMESTAMPDIFF(SECOND, rn.last_heartbeat_time, NOW()) < 300
    ORDER BY (rn.available_cpu - p_required_cpu) ASC
    LIMIT 1
    FOR UPDATE;
    
    IF p_node_id IS NULL THEN
        SET p_error_code = 404;
        SET p_error_msg = '无可用资源节点';
        ROLLBACK;
    ELSE
        -- 2. 扣减资源（使用乐观锁）
        UPDATE resource_node
        SET available_cpu = available_cpu - p_required_cpu,
            available_memory_mb = available_memory_mb - p_required_memory_mb,
            available_gpu = available_gpu - p_required_gpu,
            version = version + 1
        WHERE id = p_node_id AND version = v_current_version;
        
        IF ROW_COUNT() = 0 THEN
            SET p_error_code = 409;
            SET p_error_msg = '资源节点版本冲突，请重试';
            ROLLBACK;
        ELSE
            -- 3. 更新任务实例
            UPDATE task_instance
            SET resource_node_id = p_node_id,
                status = 'RUNNING',
                start_time = NOW()
            WHERE id = p_task_instance_id;
            
            -- 4. 更新租户配额
            SELECT tn.id INTO v_tenant_id
            FROM task_instance ti
            JOIN task t ON ti.task_id = t.id
            JOIN project p ON t.project_id = p.id
            JOIN tenant tn ON p.tenant_id = tn.id
            WHERE ti.id = p_task_instance_id;
            
            UPDATE resource_quota
            SET used_cpu = used_cpu + p_required_cpu,
                used_memory_mb = used_memory_mb + p_required_memory_mb,
                used_gpu = used_gpu + p_required_gpu,
                running_tasks = running_tasks + 1
            WHERE tenant_id = v_tenant_id;
            
            SET p_error_code = 0;
            SET p_error_msg = 'success';
            COMMIT;
        END IF;
    END IF;
END$$

DELIMITER ;

-- 7.3 任务完成资源释放存储过程
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_release_resource$$
CREATE PROCEDURE sp_release_resource(
    IN p_task_instance_id BIGINT,
    IN p_final_status VARCHAR(20),
    IN p_exit_code INT,
    OUT p_error_code INT,
    OUT p_error_msg VARCHAR(255)
)
BEGIN
    DECLARE v_node_id BIGINT;
    DECLARE v_tenant_id BIGINT;
    DECLARE v_required_cpu INT;
    DECLARE v_required_memory_mb INT;
    DECLARE v_required_gpu INT;
    DECLARE v_start_time DATETIME;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_error_code = 500;
        SET p_error_msg = '资源释放失败';
    END;
    
    START TRANSACTION;
    
    -- 1. 获取任务信息
    SELECT 
        ti.resource_node_id,
        ti.start_time,
        COALESCE(JSON_EXTRACT(t.resource_require, '$.cpu'), 0),
        COALESCE(JSON_EXTRACT(t.resource_require, '$.memory_mb'), 0),
        COALESCE(JSON_EXTRACT(t.resource_require, '$.gpu'), 0),
        tn.id
    INTO v_node_id, v_start_time, v_required_cpu, v_required_memory_mb, v_required_gpu, v_tenant_id
    FROM task_instance ti
    JOIN task t ON ti.task_id = t.id
    JOIN project p ON t.project_id = p.id
    JOIN tenant tn ON p.tenant_id = tn.id
    WHERE ti.id = p_task_instance_id
    FOR UPDATE;
    
    -- 2. 更新任务实例状态
    UPDATE task_instance
    SET status = p_final_status,
        end_time = NOW(),
        duration_ms = TIMESTAMPDIFF(MICROSECOND, v_start_time, NOW()) / 1000,
        exit_code = p_exit_code
    WHERE id = p_task_instance_id;
    
    -- 3. 归还资源到节点
    IF v_node_id IS NOT NULL THEN
        UPDATE resource_node
        SET available_cpu = available_cpu + v_required_cpu,
            available_memory_mb = available_memory_mb + v_required_memory_mb,
            available_gpu = available_gpu + v_required_gpu,
            version = version + 1
        WHERE id = v_node_id;
    END IF;
    
    -- 4. 归还租户配额
    UPDATE resource_quota
    SET used_cpu = GREATEST(0, used_cpu - v_required_cpu),
        used_memory_mb = GREATEST(0, used_memory_mb - v_required_memory_mb),
        used_gpu = GREATEST(0, used_gpu - v_required_gpu),
        running_tasks = GREATEST(0, running_tasks - 1)
    WHERE tenant_id = v_tenant_id;
    
    SET p_error_code = 0;
    SET p_error_msg = 'success';
    COMMIT;
END$$

DELIMITER ;

-- ============================================================================
-- 8. 触发器 (Triggers)
-- ============================================================================

-- 8.1 项目删除级联触发器
DELIMITER $$

DROP TRIGGER IF EXISTS trg_project_delete_cascade$$
CREATE TRIGGER trg_project_delete_cascade
BEFORE UPDATE ON project
FOR EACH ROW
BEGIN
    IF OLD.deleted = 0 AND NEW.deleted = 1 THEN
        -- 软删除项目下所有任务
        UPDATE task
        SET deleted = 1, updated_at = NOW()
        WHERE project_id = NEW.id AND deleted = 0;
        
        -- 软删除项目下所有工作流
        UPDATE workflow
        SET deleted = 1, updated_at = NOW()
        WHERE project_id = NEW.id AND deleted = 0;
    END IF;
END$$

DELIMITER ;

-- 8.2 任务实例状态变更日志触发器
DELIMITER $$

DROP TRIGGER IF EXISTS trg_task_instance_status_log$$
CREATE TRIGGER trg_task_instance_status_log
AFTER UPDATE ON task_instance
FOR EACH ROW
BEGIN
    IF OLD.status != NEW.status THEN
        INSERT INTO execution_log (
            task_instance_id, log_level, log_content, log_time, source
        ) VALUES (
            NEW.id,
            'INFO',
            CONCAT('任务状态变更: ', OLD.status, ' -> ', NEW.status),
            NOW(),
            'SYSTEM'
        );
    END IF;
END$$

DELIMITER ;

-- ============================================================================
-- 9. 初始化数据 (Initial Data)
-- ============================================================================

-- 插入示例数据将在init-data.sql中提供

-- ============================================================================
-- 脚本执行完成
-- ============================================================================

SELECT '数据库初始化完成！' AS message;
SELECT '共创建15个表、5个视图、3个存储过程、2个触发器' AS summary;
