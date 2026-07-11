-- =============================================================================
-- Migration: 心跳机制 + 自动重试（NodeHeartbeatWatchdog + TaskRetryService）
-- 对应改动：TaskInstance 实体新增 last_heartbeat_at 字段
-- =============================================================================

-- 1. 为 task_instance 添加执行器心跳时间字段
--    执行器每 10 秒更新一次；NodeHeartbeatWatchdog 据此判断任务是否"僵死"
ALTER TABLE task_instance
    ADD COLUMN last_heartbeat_at DATETIME NULL
        COMMENT '执行器最近心跳时间。执行期间每 10s 更新；超过 30s 无更新视为节点宕机，触发 FAILED 恢复和自动重试'
        AFTER retry_count;

-- 2. 为心跳超时查询加索引（NodeHeartbeatWatchdog.selectZombieRunningTasks）
--    WHERE status='RUNNING' AND last_heartbeat_at < #{deadline}
CREATE INDEX idx_task_instance_heartbeat
    ON task_instance (status, last_heartbeat_at);

-- 3. 确认 resource_node 表已有 last_heartbeat_time 字段（原始 schema 应已存在）
--    若不存在，执行以下语句（此处注释掉，避免重复添加）：
-- ALTER TABLE resource_node
--     ADD COLUMN last_heartbeat_time DATETIME NULL
--         COMMENT '节点最近心跳时间，超过 30s 未更新则由 NodeHeartbeatWatchdog 标记为 OFFLINE';

-- 4. 为节点宕机检测查询加索引（NodeHeartbeatWatchdog.selectDeadNodes）
--    WHERE status='ONLINE' AND last_heartbeat_time < #{deadline}
CREATE INDEX idx_resource_node_heartbeat
    ON resource_node (status, last_heartbeat_time);

-- 5. task 表应已有 retry_times 和 retry_interval 字段（原始 schema 应已存在）
--    验证：
-- SHOW COLUMNS FROM task LIKE 'retry%';

-- =============================================================================
-- 说明
-- =============================================================================
-- 节点心跳写入由 Worker/Executor 进程负责（向 resource_node 表更新 last_heartbeat_time）
-- 任务心跳写入由 LocalTaskRunner 的后台线程负责（向 task_instance 表更新 last_heartbeat_at）
-- NodeHeartbeatWatchdog 每 15 秒扫描：
--   1. resource_node.last_heartbeat_time < NOW()-30s → 标记 OFFLINE → 恢复其 RUNNING 任务
--   2. task_instance.last_heartbeat_at < NOW()-30s → 恢复僵尸任务（节点宕机漏检兜底）
-- ReconciliationWorker 每 60 秒扫描（兜底兜底）：
--   task.timeout_seconds 超时的 RUNNING 任务 → 标记 TIMEOUT → 触发重试
