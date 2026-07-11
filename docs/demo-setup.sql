UPDATE user SET password_hash='$2b$10$mhvk7N6/N5Pmlt1xBkhvK./HqY84fuWJq2JK8V9tF1CjrpTyEmPaS' WHERE username='admin';

INSERT INTO task (
    project_id, task_name, task_code, task_type, executor_config,
    schedule_type, timeout_seconds, retry_times, retry_interval,
    priority, resource_require, status, creator_user_id
) VALUES (
    5, 'Demo Echo', 'demo-echo', 'SHELL',
    '{"type":"SHELL","command":"echo Hello from Distributed Lite Scheduler"}',
    'MANUAL', 60, 0, 0, 8,
    '{"cpu":0,"memory_mb":0,"gpu":0}', 1, 1
);

SELECT id, task_code FROM task WHERE task_code='demo-echo';
