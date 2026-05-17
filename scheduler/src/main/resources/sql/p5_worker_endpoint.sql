-- Step2: Worker HTTP 接入地址（可选；为空时调度器使用 http://node_host:node_port）
ALTER TABLE resource_node
    ADD COLUMN worker_endpoint VARCHAR(256) NULL COMMENT 'Worker 服务根地址，如 http://10.0.0.5:9090' AFTER node_port;
