-- P3-1 task_instance 字段补齐迁移脚本（MySQL 8+）
-- 用途：与 TaskSubmitRequest -> TaskInstance 映射字段对齐

ALTER TABLE `task_instance`
    ADD COLUMN `tenant_id` BIGINT UNSIGNED NULL COMMENT '所属租户ID' AFTER `task_id`,
    ADD COLUMN `resource_requirement` JSON NULL COMMENT '资源需求（JSON快照）' AFTER `priority`,
    ADD COLUMN `executor_config` JSON NULL COMMENT '执行器配置（JSON快照）' AFTER `resource_requirement`,
    ADD COLUMN `parameters` JSON NULL COMMENT '任务参数（JSON快照）' AFTER `executor_config`,
    ADD COLUMN `submit_user_id` BIGINT UNSIGNED NULL COMMENT '提交用户ID' AFTER `trigger_user_id`,
    ADD COLUMN `submit_time` DATETIME NULL COMMENT '提交时间' AFTER `scheduled_time`;

CREATE INDEX `idx_submit_user_time` ON `task_instance` (`submit_user_id`, `submit_time`);
CREATE INDEX `idx_tenant_status_submit_time` ON `task_instance` (`tenant_id`, `status`, `submit_time`);
