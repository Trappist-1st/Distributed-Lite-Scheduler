# 数据库快速部署指南

## 📦 环境要求

- **数据库**: MySQL 8.0+ 或 PostgreSQL 14+
- **客户端**: MySQL Client / pgAdmin / DBeaver
- **操作系统**: Windows / Linux / macOS

---

## 🚀 快速开始（5分钟部署）

### 方式1: Docker 一键部署（推荐）

#### 1.1 创建 docker-compose.yml

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: scheduler-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: distributed_scheduler
      MYSQL_USER: scheduler
      MYSQL_PASSWORD: scheduler123
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./init-data.sql:/docker-entrypoint-initdb.d/02-init-data.sql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+08:00
    networks:
      - scheduler-network

  redis:
    image: redis:7.0-alpine
    container_name: scheduler-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes
    networks:
      - scheduler-network

volumes:
  mysql_data:
  redis_data:

networks:
  scheduler-network:
    driver: bridge
```

#### 1.2 启动服务

```bash
# 启动 MySQL 和 Redis
docker-compose up -d

# 查看日志（等待初始化完成）
docker-compose logs -f mysql

# 看到 "ready for connections" 表示启动成功
```

#### 1.3 验证部署

```bash
# 连接数据库
docker exec -it scheduler-mysql mysql -uscheduler -pscheduler123 distributed_scheduler

# 验证表创建
mysql> SHOW TABLES;

# 验证数据
mysql> SELECT COUNT(*) FROM user;
```

---

### 方式2: 本地 MySQL 部署

#### 2.1 创建数据库

```sql
-- 连接 MySQL（使用 root 或管理员账户）
mysql -uroot -p

-- 创建数据库
CREATE DATABASE distributed_scheduler CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户并授权
CREATE USER 'scheduler'@'%' IDENTIFIED BY 'scheduler123';
GRANT ALL PRIVILEGES ON distributed_scheduler.* TO 'scheduler'@'%';
FLUSH PRIVILEGES;

-- 退出
exit;
```

#### 2.2 执行 DDL 脚本

```bash
# Windows
mysql -uscheduler -pscheduler123 distributed_scheduler < schema.sql

# Linux/macOS
mysql -uscheduler -pscheduler123 distributed_scheduler < schema.sql
```

#### 2.3 执行初始化数据脚本

```bash
# Windows
mysql -uscheduler -pscheduler123 distributed_scheduler < init-data.sql

# Linux/macOS
mysql -uscheduler -pscheduler123 distributed_scheduler < init-data.sql
```

#### 2.4 验证部署

```sql
mysql -uscheduler -pscheduler123 distributed_scheduler

-- 查看所有表
SHOW TABLES;

-- 验证数据量
SELECT table_name, table_rows 
FROM information_schema.tables 
WHERE table_schema = 'distributed_scheduler';

-- 查看视图
SHOW FULL TABLES WHERE table_type = 'VIEW';

-- 查看存储过程
SHOW PROCEDURE STATUS WHERE db = 'distributed_scheduler';

-- 查看触发器
SHOW TRIGGERS;
```

---

### 方式3: PostgreSQL 部署

#### 3.1 创建数据库（需要先转换SQL语法）

```sql
-- 连接 PostgreSQL
psql -U postgres

-- 创建数据库
CREATE DATABASE distributed_scheduler;

-- 创建用户
CREATE USER scheduler WITH PASSWORD 'scheduler123';

-- 授权
GRANT ALL PRIVILEGES ON DATABASE distributed_scheduler TO scheduler;
```

#### 3.2 修改 schema.sql（PostgreSQL 语法调整）

主要差异：
- `AUTO_INCREMENT` → `SERIAL` 或 `BIGSERIAL`
- `TINYINT` → `SMALLINT`
- `DATETIME` → `TIMESTAMP`
- `JSON` 类型保持不变
- 存储过程语法需调整为 PL/pgSQL

#### 3.3 执行脚本

```bash
psql -U scheduler -d distributed_scheduler -f schema.sql
psql -U scheduler -d distributed_scheduler -f init-data.sql
```

---

## 🔍 验证检查清单

### 1. 表结构验证

```sql
-- 检查所有表是否创建成功（应该有14个）
SELECT COUNT(*) AS table_count FROM information_schema.tables 
WHERE table_schema = 'distributed_scheduler' AND table_type = 'BASE TABLE';

-- 预期结果: 14
```

### 2. 索引验证

```sql
-- 检查关键索引是否创建
SELECT 
    TABLE_NAME, 
    INDEX_NAME, 
    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'distributed_scheduler'
  AND INDEX_NAME IN (
      'idx_status_priority',  -- 调度器核心索引
      'idx_available_resource', -- 资源分配索引
      'uk_instance_code'        -- 幂等性索引
  )
GROUP BY TABLE_NAME, INDEX_NAME;
```

### 3. 视图验证

```sql
-- 测试视图是否可用
SELECT * FROM v_task_instance_detail LIMIT 5;
SELECT * FROM v_tenant_resource_stats;
SELECT * FROM v_resource_node_utilization;
```

### 4. 存储过程验证

```sql
-- 测试任务提交存储过程
CALL sp_submit_task_instance(1, 'MANUAL', 2, 7, @instance_id, @error_code, @error_msg);
SELECT @instance_id, @error_code, @error_msg;

-- 验证结果：error_code = 0 表示成功
```

### 5. 触发器验证

```sql
-- 测试项目删除级联
UPDATE project SET deleted = 1 WHERE id = 5;

-- 检查该项目下的任务是否被软删除
SELECT id, task_name, deleted FROM task WHERE project_id = 5;

-- 恢复数据
UPDATE project SET deleted = 0 WHERE id = 5;
UPDATE task SET deleted = 0 WHERE project_id = 5;
```

### 6. 数据完整性验证

```sql
-- 检查外键关系
SELECT 
    TABLE_NAME,
    CONSTRAINT_NAME,
    REFERENCED_TABLE_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'distributed_scheduler'
  AND REFERENCED_TABLE_NAME IS NOT NULL;
```

---

## 📊 性能测试

### 1. 索引性能测试

```sql
-- 测试调度器查询性能（应该 < 10ms）
SET profiling = 1;

SELECT * FROM task_instance
WHERE status = 'PENDING'
ORDER BY priority DESC, created_at ASC
LIMIT 100;

SHOW PROFILES;

-- 查看执行计划
EXPLAIN SELECT * FROM task_instance
WHERE status = 'PENDING'
ORDER BY priority DESC, created_at ASC
LIMIT 100;

-- 预期结果：type = 'range', Extra = 'Using index'
```

### 2. 资源分配查询性能

```sql
-- 测试资源节点查询（应该 < 5ms）
SELECT * FROM resource_node
WHERE status = 'ONLINE'
  AND available_cpu >= 4
  AND available_gpu >= 1
ORDER BY available_cpu ASC
LIMIT 1;

SHOW PROFILES;
```

### 3. 批量插入性能

```sql
-- 测试批量插入 1000 条任务实例
DELIMITER $$

CREATE PROCEDURE test_batch_insert()
BEGIN
    DECLARE i INT DEFAULT 0;
    WHILE i < 1000 DO
        INSERT INTO task_instance (
            task_id, instance_code, trigger_type, status, priority, scheduled_time
        ) VALUES (
            1, 
            CONCAT('TEST_', UNIX_TIMESTAMP(), '_', i), 
            'MANUAL', 
            'PENDING', 
            5, 
            NOW()
        );
        SET i = i + 1;
    END WHILE;
END$$

DELIMITER ;

-- 执行测试
CALL test_batch_insert();

-- 清理测试数据
DELETE FROM task_instance WHERE instance_code LIKE 'TEST_%';

-- 删除测试存储过程
DROP PROCEDURE test_batch_insert;
```

---

## 🛠️ 常见问题排查

### 问题1: 连接被拒绝

```bash
# 检查 MySQL 是否运行
docker ps | grep mysql

# 检查端口是否开放
netstat -an | grep 3306

# 解决方案：确保防火墙允许 3306 端口
```

### 问题2: 字符集问题

```sql
-- 检查数据库字符集
SHOW VARIABLES LIKE 'character%';

-- 如果不是 utf8mb4，修改配置
ALTER DATABASE distributed_scheduler CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 问题3: 存储过程执行失败

```sql
-- 查看错误信息
SHOW ERRORS;
SHOW WARNINGS;

-- 检查存储过程是否存在
SELECT ROUTINE_NAME FROM information_schema.ROUTINES 
WHERE ROUTINE_SCHEMA = 'distributed_scheduler';
```

### 问题4: 索引未生效

```sql
-- 重建索引
ALTER TABLE task_instance DROP INDEX idx_status_priority;
ALTER TABLE task_instance ADD INDEX idx_status_priority (status, priority, created_at);

-- 优化表
OPTIMIZE TABLE task_instance;
```

---

## 🔧 配置调优建议

### MySQL 配置（my.cnf 或 my.ini）

```ini
[mysqld]
# 字符集
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci

# 连接数
max_connections = 500

# InnoDB 缓冲池（根据内存调整，建议 50-70% 内存）
innodb_buffer_pool_size = 2G

# 日志配置
innodb_log_file_size = 512M
innodb_flush_log_at_trx_commit = 2

# 查询缓存（MySQL 8.0 已移除）
# query_cache_type = 1
# query_cache_size = 256M

# 慢查询日志
slow_query_log = ON
long_query_time = 1
slow_query_log_file = /var/log/mysql/slow.log

# 时区
default-time-zone = '+08:00'
```

### 连接池配置（HikariCP）

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

---

## 📚 备份与恢复

### 备份脚本

```bash
#!/bin/bash
# backup.sh

BACKUP_DIR="/backup/mysql"
DATE=$(date +%Y%m%d_%H%M%S)
DB_NAME="distributed_scheduler"

# 创建备份目录
mkdir -p $BACKUP_DIR

# 全量备份
mysqldump -uscheduler -pscheduler123 \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  $DB_NAME > $BACKUP_DIR/${DB_NAME}_${DATE}.sql

# 压缩备份文件
gzip $BACKUP_DIR/${DB_NAME}_${DATE}.sql

# 删除 30 天前的备份
find $BACKUP_DIR -name "*.sql.gz" -mtime +30 -delete

echo "Backup completed: ${DB_NAME}_${DATE}.sql.gz"
```

### 恢复脚本

```bash
#!/bin/bash
# restore.sh

BACKUP_FILE=$1
DB_NAME="distributed_scheduler"

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: ./restore.sh <backup_file.sql.gz>"
    exit 1
fi

# 解压备份文件
gunzip -c $BACKUP_FILE > /tmp/restore.sql

# 恢复数据库
mysql -uscheduler -pscheduler123 $DB_NAME < /tmp/restore.sql

# 清理临时文件
rm /tmp/restore.sql

echo "Restore completed from: $BACKUP_FILE"
```

---

## ✅ 部署完成检查

运行以下查询，确保所有组件正常：

```sql
-- 1. 检查表数量
SELECT COUNT(*) AS table_count FROM information_schema.tables 
WHERE table_schema = 'distributed_scheduler' AND table_type = 'BASE TABLE';
-- 预期: 14

-- 2. 检查视图数量
SELECT COUNT(*) AS view_count FROM information_schema.views 
WHERE table_schema = 'distributed_scheduler';
-- 预期: 5

-- 3. 检查存储过程数量
SELECT COUNT(*) AS procedure_count FROM information_schema.routines 
WHERE routine_schema = 'distributed_scheduler' AND routine_type = 'PROCEDURE';
-- 预期: 3

-- 4. 检查触发器数量
SELECT COUNT(*) AS trigger_count FROM information_schema.triggers 
WHERE trigger_schema = 'distributed_scheduler';
-- 预期: 2

-- 5. 检查初始化数据
SELECT 'user' AS table_name, COUNT(*) AS row_count FROM user
UNION ALL SELECT 'tenant', COUNT(*) FROM tenant
UNION ALL SELECT 'project', COUNT(*) FROM project
UNION ALL SELECT 'task', COUNT(*) FROM task
UNION ALL SELECT 'resource_node', COUNT(*) FROM resource_node;
```

如果所有检查都通过，恭喜您！数据库部署成功！🎉

---

## 📞 支持与反馈

如有问题，请检查：
1. `schema.sql` - 表结构定义
2. `init-data.sql` - 初始化数据
3. `DATABASE_DESIGN.md` - 完整设计文档
4. `ER_DIAGRAM.md` - ER图说明

---

**最后更新**: 2026-04-02
