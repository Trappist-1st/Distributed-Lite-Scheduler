-- P2-2 资源槽位与使用流水表（在已有库上增量执行；若表已存在请先跳过或手工调整）
-- 与 docs/schema.sql 中 4.1a / 4.1b 定义一致

CREATE TABLE IF NOT EXISTS `resource_slot` (
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

CREATE TABLE IF NOT EXISTS `resource_usage` (
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
