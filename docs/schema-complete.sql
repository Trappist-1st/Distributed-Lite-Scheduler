-- =============================================================================
-- Distributed Lite Scheduler — 完整建表脚本（幂等，支持全新安装）
-- 包含：主表 + workflow_task_instance + worker_endpoint + last_heartbeat_at
-- 数据库：MySQL 8.0+
-- 用法：mysql -u root -p your_db < schema-complete.sql
-- =============================================================================

SET NAMES utf8mb4;
SET TIME_ZONE = '+08:00';

-- ============================================================================
-- 1. 用户 & 租户模块
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(50)      NOT NULL,
    `email`           VARCHAR(100)     NOT NULL,
    `password_hash`   VARCHAR(255)     NOT NULL,
    `nickname`        VARCHAR(50),
    `avatar_url`      VARCHAR(255),
    `status`          TINYINT          NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常',
    `last_login_time` DATETIME,
    `last_login_ip`   VARCHAR(50),
    `created_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `tenant` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_name`   VARCHAR(100)    NOT NULL,
    `tenant_code`   VARCHAR(50)     NOT NULL,
    `owner_user_id` BIGINT UNSIGNED NOT NULL,
    `description`   TEXT,
    `status`        TINYINT         NOT NULL DEFAULT 1,
    `expire_time`   DATETIME,
    `max_projects`  INT             NOT NULL DEFAULT 10,
    `max_tasks`     INT             NOT NULL DEFAULT 1000,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_code`),
    KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

CREATE TABLE IF NOT EXISTS `tenant_member` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`  BIGINT UNSIGNED NOT NULL,
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `role`       VARCHAR(20)     NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER/ADMIN/MEMBER/GUEST',
    `join_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_user` (`tenant_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户成员表';

-- ============================================================================
-- 2. 项目 & 任务模块
-- ============================================================================

CREATE TABLE IF NOT EXISTS `project` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `project_name`    VARCHAR(100)    NOT NULL,
    `project_code`    VARCHAR(50)     NOT NULL,
    `description`     TEXT,
    `creator_user_id` BIGINT UNSIGNED NOT NULL,
    `status`          TINYINT         NOT NULL DEFAULT 1,
    `extra_config`    JSON,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_id`, `project_code`),
    KEY `idx_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

CREATE TABLE IF NOT EXISTS `task` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `project_id`       BIGINT UNSIGNED NOT NULL,
    `task_name`        VARCHAR(100)    NOT NULL,
    `task_code`        VARCHAR(50)     NOT NULL,
    `task_type`        VARCHAR(20)     NOT NULL COMMENT 'SHELL/PYTHON/DOCKER',
    `executor_config`  JSON            NOT NULL,
    `schedule_type`    VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    `cron_expression`  VARCHAR(100),
    `timeout_seconds`  INT             NOT NULL DEFAULT 3600,
    `retry_times`      INT             NOT NULL DEFAULT 0,
    `retry_interval`   INT             NOT NULL DEFAULT 60,
    `priority`         INT             NOT NULL DEFAULT 5 COMMENT '1-10',
    `resource_require` JSON,
    `alert_on_failure` TINYINT         NOT NULL DEFAULT 0,
    `alert_on_timeout` TINYINT         NOT NULL DEFAULT 0,
    `description`      TEXT,
    `creator_user_id`  BIGINT UNSIGNED NOT NULL,
    `status`           TINYINT         NOT NULL DEFAULT 1,
    `version`          INT             NOT NULL DEFAULT 0,
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_code` (`project_id`, `task_code`),
    KEY `idx_priority` (`priority`),
    KEY `idx_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义表';

-- task_instance：含 P3-1 迁移列（tenant_id/submit_user_id/resource_requirement/executor_config/parameters/submit_time）
-- 及心跳迁移列（last_heartbeat_at）
CREATE TABLE IF NOT EXISTS `task_instance` (
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`            BIGINT UNSIGNED,
    `workflow_instance_id` BIGINT UNSIGNED,
    `instance_code`        VARCHAR(100)    NOT NULL,
    `trigger_type`         VARCHAR(20)     NOT NULL COMMENT 'MANUAL/CRON/DEPENDENCY/API',
    `trigger_user_id`      BIGINT UNSIGNED,
    `submit_user_id`       BIGINT UNSIGNED,
    `status`               VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                           COMMENT 'PENDING/DISPATCHED/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT',
    `priority`             INT             NOT NULL DEFAULT 5,
    `resource_requirement` JSON,
    `executor_config`      JSON,
    `parameters`           JSON,
    `resource_node_id`     BIGINT UNSIGNED,
    `scheduled_time`       DATETIME,
    `submit_time`          DATETIME,
    `start_time`           DATETIME,
    `end_time`             DATETIME,
    `duration_ms`          BIGINT,
    `exit_code`            INT,
    `error_message`        TEXT,
    `retry_count`          INT             NOT NULL DEFAULT 0,
    `last_heartbeat_at`    DATETIME        NULL
                           COMMENT '执行器心跳时间；超过 30s 未更新由 Watchdog 判定为僵尸任务',
    `version`              INT             NOT NULL DEFAULT 0,
    `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_code` (`instance_code`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_workflow_instance` (`workflow_instance_id`),
    KEY `idx_status_priority` (`status`, `priority`, `created_at`),
    KEY `idx_resource_node` (`resource_node_id`),
    KEY `idx_submit_user_time` (`submit_user_id`, `submit_time`),
    KEY `idx_tenant_status_submit_time` (`tenant_id`, `status`, `submit_time`),
    KEY `idx_heartbeat` (`status`, `last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务实例表';

CREATE TABLE IF NOT EXISTS `task_dependency` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `parent_task_id`  BIGINT UNSIGNED NOT NULL,
    `child_task_id`   BIGINT UNSIGNED NOT NULL,
    `dependency_type` VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parent_child` (`parent_task_id`, `child_task_id`),
    KEY `idx_child_task` (`child_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖表';

CREATE TABLE IF NOT EXISTS `task_status_change_log` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_instance_id` BIGINT UNSIGNED NOT NULL,
    `from_status`      VARCHAR(20)     NOT NULL,
    `to_status`        VARCHAR(20)     NOT NULL,
    `trigger_source`   VARCHAR(20)     NOT NULL COMMENT 'SCHEDULER/WORKER/SYSTEM/API/WATCHDOG',
    `reason`           VARCHAR(500),
    `operator_user_id` BIGINT UNSIGNED,
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_instance_created` (`task_instance_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态变更审计表';

-- ============================================================================
-- 3. 工作流模块
-- ============================================================================

CREATE TABLE IF NOT EXISTS `workflow` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `project_id`        BIGINT UNSIGNED NOT NULL,
    `workflow_name`     VARCHAR(100)    NOT NULL,
    `workflow_code`     VARCHAR(50)     NOT NULL,
    `description`       TEXT,
    `dag_json`          JSON            NOT NULL,
    `schedule_type`     VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    `cron_expression`   VARCHAR(100),
    `next_schedule_time` DATETIME,
    `timeout_seconds`   INT             NOT NULL DEFAULT 7200,
    `alert_on_failure`  TINYINT         NOT NULL DEFAULT 0,
    `creator_user_id`   BIGINT UNSIGNED NOT NULL,
    `status`            TINYINT         NOT NULL DEFAULT 1,
    `version`           INT             NOT NULL DEFAULT 0,
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_code` (`project_id`, `workflow_code`),
    KEY `idx_schedule` (`status`, `next_schedule_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义表';

CREATE TABLE IF NOT EXISTS `workflow_instance` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `workflow_id`     BIGINT UNSIGNED NOT NULL,
    `instance_code`   VARCHAR(100),
    `trigger_type`    VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    `trigger_user_id` BIGINT UNSIGNED,
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING/PREPARING/RUNNING/PAUSED/SUCCESS/FAILED/PARTIAL_SUCCESS/CANCELLED',
    `total_tasks`     INT             NOT NULL DEFAULT 0,
    `success_tasks`   INT             NOT NULL DEFAULT 0,
    `failed_tasks`    INT             NOT NULL DEFAULT 0,
    `start_time`      DATETIME,
    `end_time`        DATETIME,
    `duration_ms`     BIGINT,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_code` (`instance_code`),
    KEY `idx_workflow_id` (`workflow_id`),
    KEY `idx_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流实例表';

-- 工作流任务实例（DAG 节点执行快照）
CREATE TABLE IF NOT EXISTS `workflow_task_instance` (
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `workflow_instance_id` BIGINT UNSIGNED NOT NULL,
    `task_name`            VARCHAR(100)    NOT NULL,
    `task_definition`      JSON            NOT NULL COMMENT '任务定义 JSON 快照',
    `status`               VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                           COMMENT 'PENDING/DISPATCHED/RUNNING/SUCCESS/FAILED/SKIPPED',
    `layer_index`          INT             NOT NULL,
    `retry_count`          INT             NOT NULL DEFAULT 0,
    `task_instance_id`     BIGINT UNSIGNED COMMENT '关联的 task_instance.id',
    `start_time`           DATETIME,
    `end_time`             DATETIME,
    `duration_seconds`     INT,
    `exit_code`            INT,
    `output`               TEXT,
    `error_message`        TEXT,
    `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_task` (`workflow_instance_id`, `task_name`),
    KEY `idx_workflow_instance` (`workflow_instance_id`),
    KEY `idx_status` (`status`),
    KEY `idx_layer_index` (`layer_index`),
    KEY `idx_task_instance` (`task_instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流任务实例表（DAG 节点）';

-- ============================================================================
-- 4. 资源模块
-- ============================================================================

-- worker_endpoint：Worker HTTP 根地址，含 p5 迁移列
CREATE TABLE IF NOT EXISTS `resource_node` (
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `node_name`           VARCHAR(100)    NOT NULL,
    `node_host`           VARCHAR(100)    NOT NULL,
    `node_port`           INT             NOT NULL,
    `worker_endpoint`     VARCHAR(256)    NULL COMMENT 'Worker 服务根地址，为空时取 http://host:port',
    `node_type`           VARCHAR(20)     NOT NULL COMMENT 'CPU/GPU/MIXED',
    `total_cpu`           INT             NOT NULL,
    `total_memory_mb`     INT             NOT NULL,
    `total_gpu`           INT             NOT NULL DEFAULT 0,
    `gpu_model`           VARCHAR(50),
    `available_cpu`       INT             NOT NULL,
    `available_memory_mb` INT             NOT NULL,
    `available_gpu`       INT             NOT NULL DEFAULT 0,
    `status`              VARCHAR(20)     NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINE/MAINTENANCE',
    `last_heartbeat_time` DATETIME,
    `labels`              JSON,
    `version`             INT             NOT NULL DEFAULT 0,
    `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_host_port` (`node_host`, `node_port`),
    KEY `idx_status_heartbeat` (`status`, `last_heartbeat_time`),
    KEY `idx_available_resource` (`available_cpu`, `available_gpu`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源节点表';

CREATE TABLE IF NOT EXISTS `resource_slot` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `node_id`      BIGINT UNSIGNED NOT NULL,
    `resource_type` VARCHAR(20)    NOT NULL COMMENT 'CPU/GPU/MEMORY',
    `total`        INT             NOT NULL,
    `available`    INT             NOT NULL,
    `reserved_qty` INT             NOT NULL DEFAULT 0,
    `version`      INT             NOT NULL DEFAULT 0,
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_resource_type` (`node_id`, `resource_type`),
    KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源槽位表';

CREATE TABLE IF NOT EXISTS `resource_usage` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`        BIGINT UNSIGNED NOT NULL,
    `task_instance_id` BIGINT UNSIGNED NOT NULL,
    `node_id`          BIGINT UNSIGNED NOT NULL,
    `cpu_used`         INT             NOT NULL DEFAULT 0,
    `memory_mb_used`   INT             NOT NULL DEFAULT 0,
    `gpu_used`         INT             NOT NULL DEFAULT 0,
    `status`           VARCHAR(32)     NOT NULL COMMENT 'RESERVED/RUNNING/RELEASED/FAILED',
    `reason`           VARCHAR(255),
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `released_at`      DATETIME(3),
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_task_instance` (`task_instance_id`),
    KEY `idx_node_status` (`node_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源使用流水表';

CREATE TABLE IF NOT EXISTS `resource_quota` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`        BIGINT UNSIGNED NOT NULL,
    `max_cpu`          INT             NOT NULL DEFAULT 10,
    `max_memory_mb`    INT             NOT NULL DEFAULT 10240,
    `max_gpu`          INT             NOT NULL DEFAULT 0,
    `max_running_tasks` INT            NOT NULL DEFAULT 50,
    `max_pending_tasks` INT            NOT NULL DEFAULT 500,
    `used_cpu`         INT             NOT NULL DEFAULT 0,
    `used_memory_mb`   INT             NOT NULL DEFAULT 0,
    `used_gpu`         INT             NOT NULL DEFAULT 0,
    `running_tasks`    INT             NOT NULL DEFAULT 0,
    `version`          INT             NOT NULL DEFAULT 0,
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户资源配额表';

-- ============================================================================
-- 5. 监控 & 日志（Schema 预留，服务层待实现）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `execution_log` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_instance_id` BIGINT UNSIGNED NOT NULL,
    `log_level`        VARCHAR(10)     NOT NULL COMMENT 'DEBUG/INFO/WARN/ERROR',
    `log_content`      TEXT            NOT NULL,
    `log_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `source`           VARCHAR(20)     NOT NULL COMMENT 'STDOUT/STDERR/SYSTEM',
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_instance` (`task_instance_id`, `log_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志表';

CREATE TABLE IF NOT EXISTS `alert_rule` (
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`            BIGINT UNSIGNED NOT NULL,
    `rule_name`            VARCHAR(100)    NOT NULL,
    `rule_type`            VARCHAR(20)     NOT NULL,
    `target_type`          VARCHAR(20)     NOT NULL,
    `target_id`            BIGINT UNSIGNED,
    `condition_config`     JSON            NOT NULL,
    `notification_channels` JSON           NOT NULL,
    `notification_users`   JSON,
    `status`               TINYINT         NOT NULL DEFAULT 1,
    `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则表（Schema 预留）';

CREATE TABLE IF NOT EXISTS `metric_snapshot` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `resource_type` VARCHAR(20)     NOT NULL,
    `resource_id`   BIGINT UNSIGNED NOT NULL,
    `metric_type`   VARCHAR(50)     NOT NULL,
    `metric_value`  DECIMAL(10, 2)  NOT NULL,
    `snapshot_time` DATETIME        NOT NULL,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_resource` (`resource_type`, `resource_id`, `snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='性能指标快照表（Schema 预留）';

-- ============================================================================
-- 6. 视图
-- ============================================================================

CREATE OR REPLACE VIEW v_task_instance_detail AS
SELECT
    ti.id              AS instance_id,
    ti.instance_code,
    ti.status          AS instance_status,
    ti.start_time,
    ti.end_time,
    ti.duration_ms,
    ti.last_heartbeat_at,
    t.id               AS task_id,
    t.task_name,
    t.task_type,
    p.id               AS project_id,
    p.project_name,
    tn.id              AS tenant_id,
    tn.tenant_name,
    rn.id              AS node_id,
    rn.node_name,
    rn.node_host,
    u.id               AS submit_user_id,
    u.username         AS submit_username
FROM task_instance ti
JOIN task t ON ti.task_id = t.id AND t.deleted = 0
JOIN project p ON t.project_id = p.id AND p.deleted = 0
JOIN tenant tn ON p.tenant_id = tn.id AND tn.deleted = 0
LEFT JOIN resource_node rn ON ti.resource_node_id = rn.id
LEFT JOIN user u ON ti.submit_user_id = u.id AND u.deleted = 0;

CREATE OR REPLACE VIEW v_tenant_resource_stats AS
SELECT
    tn.id                  AS tenant_id,
    tn.tenant_name,
    rq.max_cpu,
    rq.max_memory_mb,
    rq.max_gpu,
    rq.used_cpu,
    rq.used_memory_mb,
    rq.used_gpu,
    ROUND(rq.used_cpu * 100.0 / NULLIF(rq.max_cpu, 0), 2)             AS cpu_usage_percent,
    ROUND(rq.used_memory_mb * 100.0 / NULLIF(rq.max_memory_mb, 0), 2) AS memory_usage_percent,
    rq.running_tasks,
    rq.max_running_tasks
FROM tenant tn
JOIN resource_quota rq ON tn.id = rq.tenant_id
WHERE tn.deleted = 0;

CREATE OR REPLACE VIEW v_resource_node_utilization AS
SELECT
    rn.id          AS node_id,
    rn.node_name,
    rn.node_host,
    rn.node_type,
    rn.total_cpu,
    rn.available_cpu,
    rn.total_cpu - rn.available_cpu                                                    AS used_cpu,
    ROUND((rn.total_cpu - rn.available_cpu) * 100.0 / NULLIF(rn.total_cpu, 0), 2)     AS cpu_utilization,
    rn.total_memory_mb,
    rn.available_memory_mb,
    ROUND((rn.total_memory_mb - rn.available_memory_mb) * 100.0 / NULLIF(rn.total_memory_mb, 0), 2) AS memory_utilization,
    rn.status,
    rn.last_heartbeat_time,
    TIMESTAMPDIFF(SECOND, rn.last_heartbeat_time, NOW()) AS heartbeat_age_seconds
FROM resource_node rn;

SELECT '数据库初始化完成（schema-complete.sql）' AS message;
