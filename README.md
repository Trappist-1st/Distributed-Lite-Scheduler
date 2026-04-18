# Distributed Lite Scheduler V1

> 🎯 **面向中小团队的生产级轻量分布式调度平台**  
> 对标：XXL-Job（简化版）+ Kubernetes Job Scheduler（概念级）+ Apache Airflow（轻量版）

[![Language](https://img.shields.io/badge/Language-Java-blue)](https://www.java.com/)
[![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%204.0-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-green)](#license)

---

## 📋 目录

- [产品概述](#产品概述)
- [系统架构](#系统架构)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [主要功能模块](#主要功能模块)
- [技术栈](#技术栈)
- [开发计划](#开发计划)
- [文档指南](#文档指南)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 🎯 产品概述

### 产品定位

Distributed Lite Scheduler 是一个为中小团队设计的轻量级分布式任务调度平台，适用于如下场景：

#### 目标用户

- 📊 **数据团队**：ETL任务编排、模型训练调度、数据同步
- 💻 **开发团队**：定时任务管理、批处理任务执行、定期清理任务
- 🤖 **AI团队**：GPU资源调度、训练任务排队、推理任务管理

#### 为什么选择本项目而不是XXL-Job？

| 对标项目 | Distributed Lite Scheduler | 优势 |
|---------|---------------------------|------|
| XXL-Job | ✅ 任务调度、定时任务 | **+资源感知、多租户、DAG工作流** |
| Kubernetes | ✅ 分布式执行、资源管理 | **轻量级、易部署、学习曲线平缓** |
| Airflow | ✅ DAG工作流、依赖编排 | **更轻松、更易集成、资源调度** |

### 核心价值主张

✅ **资源感知调度** - CPU/GPU/内存限制，防止任务互相压垮  
✅ **DAG工作流支持** - 任务依赖编排，支持复杂流程  
✅ **多租户资源隔离** - 每个租户独立的资源配额和成本管理  
✅ **可视化监控面板** - 实时任务状态、资源使用、告警  
✅ **插件化执行器** - 支持Shell/Python/Docker/HTTP等多种执行方式  
✅ **生产级可靠性** - 分布式锁、乐观锁、幂等性设计  

---

## 🏗️ 系统架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      Web UI / CLI / OpenAPI                  │
│              [任务管理] [资源监控] [报表统计]                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                   API Gateway (Spring Cloud Gateway)         │
│              [限流] [认证] [熔断] [日志追踪]                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
│ Scheduler    │ │ Executor │ │ Monitor     │
│ 调度核心模块    │ │ 执行器集群 │ │ 监控告警模块  │
│              │ │          │ │            │
│ • Leader选举 │ │• 任务执行  │ │• 实时告警   │
│ • DAG解析    │ │• 状态上报  │ │• 性能采集   │
│ • 调度决策    │ │• 日志收集  │ │• 可视化    │
└───────┬──────┘ └────┬─────┘ └──────┬──────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
│ MySQL/PG     │ │ Redis    │ │ MinIO/OSS   │
│ 元数据存储     │ │ 分布式锁  │ │ 日志存储     │
│              │ │ 任务队列  │ │ 归档数据     │
│ • 任务定义    │ │ 状态缓存  │ │            │
│ • 执行历史    │ │          │ │            │
│ • 资源配置    │ │          │ │            │
└──────────────┘ └──────────┘ └─────────────┘
```

### 核心设计原则

1. **高可用** - 支持多Scheduler实例，通过Redis分布式锁自动选举主节点
2. **高可靠** - 任务持久化、断点续传、自动重试、失败告警
3. **高效率** - 异步处理、分批操作、缓存策略、资源预留
4. **易运维** - 清晰的日志、指标可观测、快速问题定位

---

## ⭐ 核心特性

### 1. 资源感知调度（Resource-Aware Scheduling）

本系统区别于其他调度器的最大特点：**不仅调度任务，还调度资源**

```
传统调度器 (XXL-Job):
任务 A: CPU 4核 → 节点1 (已有8个任务，每个2核)
       内存不足,任务卡顿 ❌

本系统 (Distributed Lite Scheduler):
任务 A: 需要 CPU 4核、内存 8GB
检查配额 ✅ → 查询可用节点 ✅ → 预留资源 ✅ → 调度执行 ✅
不会因资源争抢而导致任务失败
```

**支持管理的异构资源：**
- CPU 核数
- 内存 (GB/MB)
- GPU 卡数 + GPU型号

### 2. 多租户隔离（Multi-Tenancy）

每个租户拥有：
- 独立的资源配额上限 (CPU/内存/GPU)
- 独立的任务命名空间
- 独立的成本统计和审计日志
- **确保资源公平分配，防止某个租户独占所有资源**

### 3. DAG工作流编排（Workflow DAG）

支持复杂的任务依赖关系：

```
    ┌─→ Task B
Task A─┤
    └─→ Task C
         ↓
      Task D
```

特性：
- 拓扑排序自动解析依赖
- 支持条件分支和动态分支
- 失败自动重试或告警
- 支持手动重跑某个失败tasks

### 4. 多种执行器（Pluggable Executors）

- **Shell Executor** - 执行Shell脚本
- **Python Executor** - 执行Python脚本和数据处理任务
- **Docker Executor** - 容器化执行，环境隔离
- **HTTP Executor** - 远程调用外部服务
- **Java Executor** - 调用Java方法/类（扩展点）

### 5. 分布式一致性保障（Distributed Consistency）

**Redis分布式锁 (Redisson)**
- **Scheduler Leader选举**：多实例环境下通过Redis锁自动选举主调度器，避免重复调度
- **任务调度互斥锁**：防止同一任务被多个Scheduler实例同时调度
- **资源分配锁**：保证多个任务竞争同一资源节点时的互斥访问
- **Watch Dog自动续期**：Redisson自动续期机制，防止业务执行时间超过锁超时时间

**数据库乐观锁**
- 任务状态更新时的并发控制
- 资源节点可用量更新的并发控制
- 防止并发更新导致的数据不一致

**幂等性设计**
- 任务ID去重，防止重复执行
- 消息消费端的幂等性处理

### 6. 高级调度算法 (Advanced Scheduling Algorithms)

- **Fair Scheduling** - 多租户公平调度，避免某个租户任务堆积
- **Priority Queue** - 优先级队列（堆实现），支持任务优先级
- **Backfill Scheduling** - 资源填充算法（借鉴HPC调度），充分利用资源空间
- **Preemption** - 任务抢占机制（可选），高优先级任务可抢占低优先级任务

---

## 🚀 快速开始

### 环境要求

- **Java**: 21+
- **Maven**: 3.6+
- **MySQL**: 5.7+ 或 PostgreSQL 12+
- **Redis**: 6.0+
- **Docker** (可选，用于容器化执行或开发环境)

### 本地开发环境搭建

#### 1. 克隆仓库

```bash
git clone https://github.com/yourusername/Distributed-Lite-Scheduler-V1.git
cd Distributed-Lite-Scheduler-V1
```

#### 2. 配置数据库

**创建数据库和表**

```bash
# 使用提供的SQL脚本初始化
mysql -u root -p < docs/schema.sql
mysql -u root -p < docs/init-data.sql

# 或用PostgreSQL
psql -U postgres -f docs/schema.sql
psql -U postgres -f docs/init-data.sql
```

#### 3. 配置Redis

```bash
# 本地启动Redis（需预装Redis）
redis-server

# 或使用Docker
docker run -d -p 6379:6379 redis:7.0
```

#### 4. 修改配置文件

编辑 `src/main/resources/application.yaml`：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/scheduler_db
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver
  
  redis:
    host: localhost
    port: 6379
    password: 
    database: 0

mybatis-plus:
  mapper-locations: classpath:/mapper/**/*.xml
  type-aliases-package: com.imperium.distributed_lite_scheduler_v1.model.entity

logging:
  level:
    com.imperium: DEBUG
    org.springframework: INFO
```

#### 5. 编译和运行

```bash
# 清理并编译
mvn clean install

# 启动应用
mvn spring-boot:run

# 或使用IDE直接运行
# DistributedLiteSchedulerV1Application.java
```

#### 6. 验证启动

```bash
# 检查应用是否启动成功
curl http://localhost:8080/api/health

# 返回示例
# {"status": "UP", "database": "MySQL", "redis": "connected"}
```

---

## 📁 项目结构

```
Distributed-Lite-Scheduler-V1/
├── docs/                                    # 文档目录
│   ├── PROJECT_PLAN.md                     # 项目规划
│   ├── ENTITY_CLASSES.md                   # 实体类设计
│   ├── DATABASE_DESIGN.md                  # 数据库设计详解
│   ├── DATABASE_SUMMARY.md                 # 数据库表汇总
│   ├── TASK_STATE_MACHINE_ROLLOUT_DESIGN.md  # 任务状态机设计
│   ├── PROJECT_TASK_CRUD_ROLLOUT_DESIGN.md   # 项目-任务CRUD设计
│   ├── RESOURCE_NODE_MANAGEMENT_ROLLOUT_DESIGN.md  # 资源节点管理设计
│   ├── RESOURCE_SLOT_MANAGEMENT_ROLLOUT_DESIGN.md  # 资源槽位管理设计
│   ├── TENANT_RESOURCE_QUOTA_ROLLOUT_DESIGN.md     # 租户配额管理设计
│   ├── THREE_RESOURCE_COMPONENTS_GUIDE.md   # 三大资源组件详解指南
│   ├── MULTI_TENANCY_ROLLOUT_DESIGN.md     # 多租户设计
│   ├── REDIS_DESIGN.md                     # Redis分布式锁设计
│   ├── BUSINESS_LOGIC.md                   # 业务逻辑总结
│   ├── ADVANCED_FEATURES.md                # 高级功能说明
│   ├── DEPLOYMENT_GUIDE.md                 # 部署指南
│   ├── BUGFIX_GUIDE.md                     # Bug修复指南
│   ├── schema.sql                          # 数据库Schema
│   └── init-data.sql                       # 初始化数据
│
├── src/main/java/com/imperium/distributed_lite_scheduler_v1/
│   ├── DistributedLiteSchedulerV1Application.java  # 主启动类
│   ├── config/                             # 配置类
│   │   ├── CryptoConfig.java               # 加密配置
│   │   └── SecurityConfig.java             # Spring Security配置
│   │
│   ├── controller/                         # REST控制器
│   │   ├── AuthController.java             # 认证接口
│   │   ├── UserController.java             # 用户管理接口
│   │   ├── TenantController.java           # 租户管理接口
│   │   ├── ProjectController.java          # 项目管理接口
│   │   ├── TaskController.java             # 任务定义接口
│   │   ├── TaskInstanceController.java     # 任务实例接口
│   │   ├── ResourceController.java         # 资源管理接口 (节点/槽位/配额)
│   │
│   ├── service/                            # 业务逻辑层
│   │   ├── UserService.java                # 用户服务接口
│   │   ├── TenantService.java              # 租户服务接口
│   │   ├── ProjectService.java             # 项目服务接口
│   │   ├── TaskService.java                # 任务定义服务接口
│   │   ├── TaskInstanceService.java        # 任务实例服务接口
│   │   ├── ResourceNodeService.java        # 资源节点服务接口
│   │   ├── ResourceSlotService.java        # 资源槽位服务接口
│   │   ├── ResourceQuotaService.java       # 资源配额服务接口
│   │   │
│   │   └── impl/                           # 服务实现
│   │       ├── UserServiceImpl.java
│   │       ├── TenantServiceImpl.java
│   │       ├── ProjectServiceImpl.java
│   │       ├── TaskServiceImpl.java
│   │       ├── TaskInstanceServiceImpl.java
│   │       ├── ResourceNodeServiceImpl.java
│   │       ├── ResourceSlotServiceImpl.java
│   │       └── ResourceQuotaServiceImpl.java
│   │
│   ├── mapper/                             # MyBatis+ Mapper接口
│   │   ├── UserMapper.java
│   │   ├── TenantMapper.java
│   │   ├── ProjectMapper.java
│   │   ├── TaskMapper.java
│   │   ├── TaskInstanceMapper.java
│   │   ├── TaskStatusChangeLogMapper.java
│   │   ├── ResourceNodeMapper.java
│   │   └── TenantMemberMapper.java
│   │
│   ├── model/                              # 数据模型
│   │   ├── entity/                         # JPA/MyBatis实体类
│   │   │   ├── User.java
│   │   │   ├── Tenant.java
│   │   │   ├── TenantMember.java
│   │   │   ├── Project.java
│   │   │   ├── Task.java
│   │   │   ├── TaskInstance.java
│   │   │   ├── TaskStatusChangeLog.java
│   │   │   ├── ResourceNode.java
│   │   │   ├── ResourceSlot.java
│   │   │   ├── ResourceUsage.java
│   │   │   └── ResourceQuota.java
│   │   │
│   │   └── dto/                            # 数据传输对象
│   │       ├── CreateUserRequest.java
│   │       ├── CreateTenantRequest.java
│   │       ├── CreateProjectRequest.java
│   │       ├── CreateTaskRequest.java
│   │       └── (其他DTO...)
│   │
│   ├── security/                           # 安全相关
│   │   ├── JwtTokenProvider.java           # JWT令牌提供者
│   │   ├── JwtUserPrincipal.java           # JWT用户信息
│   │   ├── TenantAccessGuard.java          # 租户访问控制
│   │   └── SecurityConstants.java          # 安全常量
│   │
│   ├── exception/                          # 异常处理
│   │   ├── GlobalExceptionHandler.java     # 全局异常处理器
│   │   ├── SchedulerException.java         # 调度器异常
│   │   └── ResourceAllocationException.java # 资源分配异常
│   │
│   └── utils/                              # 工具类
│       ├── Result.java                     # API通用响应
│       ├── ResultCode.java                 # 响应码定义
│       ├── UUIDUtil.java                   # UUID生成
│       ├── JwtUtil.java                    # JWT工具
│       └── EncryptionUtil.java             # 加密工具
│
├── src/main/resources/
│   ├── application.yaml                    # 应用配置文件
│   └── mapper/                             # MyBatis SQL映射文件
│
├── src/test/java/                          # 单元测试
├── pom.xml                                 # Maven依赖配置
├── mvnw / mvnw.cmd                         # Maven Wrapper脚本
└── README.md                               # 本文件
```

---

## 🔧 主要功能模块

### 1. 用户与认证模块 (User & Authentication)

- JWT令牌认证
- 用户注册、登录、登出
- 密码加密存储

**API示例**
```bash
# 用户登录
POST /api/auth/login
{
  "username": "admin",
  "password": "password123"
}

# 响应
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 86400
}
```

### 2. 多租户管理模块 (Multi-Tenancy)

- 租户创建、编辑、删除
- 租户成员管理
- 租户隔离和访问控制

**API示例**
```bash
# 创建租户
POST /api/tenant
{
  "tenantName": "DataTeam",
  "tenantCode": "data_team",
  "description": "Company data processing team"
}

# 响应
{
  "id": 1,
  "tenantName": "DataTeam",
  "tenantCode": "data_team",
  "status": 1
}
```

### 3. 项目管理模块 (Project Management)

- 项目CRUD操作
- 项目成员权限管理
- 项目级别的任务组织

**API示例**
```bash
# 创建项目
POST /api/project
{
  "tenantId": 1,
  "projectName": "ETL Pipeline",
  "projectCode": "etl_01",
  "description": "Daily data ETL process"
}
```

### 4. 任务定义与实例模块 (Task Definition & Instance)

- 任务定义（模板）
- 任务实例执行（记录）
- 任务状态机管理
- 任务重试、暂停、恢复

**任务状态流转**
```
CREATED → SUBMITTED → SCHEDULED → RUNNING → SUCCESS/FAILED
           ↓
        PAUSED → RESUMED → RUNNING
```

### 5. 资源管理模块 (Resource Management) ⭐

这是系统的核心竞争力，分为三个子系统：

#### 5.1 资源节点管理 (P2-1)
- Worker节点的注册与去重
- 节点心跳上报与健康检测
- 节点状态维护（ONLINE/OFFLINE/MAINTENANCE）

#### 5.2 资源槽位管理 (P2-2)
- CPU/GPU/内存等资源的预留与释放
- 资源使用记录（审计基础）
- 防止资源超分配

#### 5.3 租户资源配额管理 (P2-3)
- 多租户资源上限配置
- 预检查配额是否超额
- 资源使用统计和对账

**API示例**
```bash
# 注册资源节点
POST /api/resource/register
{
  "nodeName": "worker-01",
  "nodeHost": "192.168.1.10",
  "nodePort": 9090,
  "nodeType": "CPU",
  "totalCpu": 8,
  "totalMemoryMb": 16384,
  "totalGpu": 0
}

# 预留资源
POST /api/resource/reserve
{
  "tenantId": 1,
  "taskInstanceId": 123,
  "resourceRequirement": {
    "cpu": 2,
    "memoryMb": 4096,
    "gpu": 0
  }
}
```

### 6. 调度引擎模块 (Scheduler Engine) - 规划中

- DAG解析与依赖管理
- Leader选举（Redis分布式锁）
- 高级调度算法实现
- 任务分配决策

---

## 💻 技术栈

### 后端技术

| 技术                   | 版本 | 用途 |
|----------------------|------|------|
| **Java**             | 21 | 编程语言 |
| **Spring Boot**      | 4.0.5 | Web框架 |
| **Spring Security**  | 6.x | 认证和授权 |
| **MyBatis**          | 4.0 | ORM框架 |
| **MyBatis Plus**     | 3.5.15 | ORM增强框架 |
| **MySQL/PostgreSQL** | 5.7+/12+ | 关系数据库 |
| **Redis**            | 6.0+ | 分布式锁、缓存、队列 |
| **Redisson**         | 3.x | Redis客户端 |
| **JJWT**             | 0.12.6 | JWT令牌处理 |
| **Lombok**           | 1.18.44 | 代码简化 |
| **Validation**       | 4.0 | 参数校验 |

### 开发工具

| 工具 | 用途 |
|------|------|
| **Maven** | 项目构建和依赖管理 |
| **Git** | 版本控制 |
| **Docker** | 容器化 (可选) |
| **IntelliJ IDEA** | IDE (推荐) |

---

## 📅 开发计划

### Phase 1: 核心平台基础（已完成/进行中）

- [x] 系统架构设计
- [x] 数据库表设计
- [x] 用户认证与授权
- [x] 租户隔离框架
- [x] 项目管理模块
- [ ] 任务定义与实例

### Phase 2: 资源管理系统（规划中）

- [ ] **P2-1** 资源节点管理 - Worker注册、心跳、健康检测
- [ ] **P2-2** 资源槽位管理 - 资源预留、释放、追踪
- [ ] **P2-3** 租户资源配额 - 配额管理、超额检查、成本控制

### Phase 3: 调度引擎（规划中）

- [ ] DAG工作流解析
- [ ] 高级调度算法 (Fair Scheduling, Backfill, Preemption)
- [ ] Leader选举与分布式锁
- [ ] 任务分配决策引擎

### Phase 4: 监控与运维（规划中）

- [ ] 可视化仪表板
- [ ] 实时告警系统
- [ ] 性能指标收集
- [ ] 日志查询与分析

### Phase 5: 增强功能（规划中）

- [ ] 插件化执行器 (Shell/Python/Docker/HTTP)
- [ ] 自动重试与降级
- [ ] 任务链路追踪
- [ ] API限流与熔断

---

## 📚 文档指南

本项目提供了详细的设计文档，建议按照以下顺序阅读：

### 快速入门
1. 本 README 文件 - 项目概览
2. [PROJECT_PLAN.md](docs/PROJECT_PLAN.md) - 项目规划详情

### 架构与设计
3. [DATABASE_DESIGN.md](docs/DATABASE_DESIGN.md) - 数据库完整设计
4. [ENTITY_CLASSES.md](docs/ENTITY_CLASSES.md) - 实体类详解
5. [BUSINESS_LOGIC.md](docs/BUSINESS_LOGIC.md) - 业务逻辑整体梳理

### 核心功能设计
6. [TASK_STATE_MACHINE_ROLLOUT_DESIGN.md](docs/TASK_STATE_MACHINE_ROLLOUT_DESIGN.md) - 任务状态机
7. [PROJECT_TASK_CRUD_ROLLOUT_DESIGN.md](docs/PROJECT_TASK_CRUD_ROLLOUT_DESIGN.md) - 项目任务管理

### 资源管理（⭐ 系统核心）
8. [THREE_RESOURCE_COMPONENTS_GUIDE.md](docs/THREE_RESOURCE_COMPONENTS_GUIDE.md) - **三大资源组件详解**（推荐先读）
9. [RESOURCE_NODE_MANAGEMENT_ROLLOUT_DESIGN.md](docs/RESOURCE_NODE_MANAGEMENT_ROLLOUT_DESIGN.md) - 资源节点管理
10. [RESOURCE_SLOT_MANAGEMENT_ROLLOUT_DESIGN.md](docs/RESOURCE_SLOT_MANAGEMENT_ROLLOUT_DESIGN.md) - 资源槽位管理
11. [TENANT_RESOURCE_QUOTA_ROLLOUT_DESIGN.md](docs/TENANT_RESOURCE_QUOTA_ROLLOUT_DESIGN.md) - 租户配额管理

### 高级话题
12. [MULTI_TENANCY_ROLLOUT_DESIGN.md](docs/MULTI_TENANCY_ROLLOUT_DESIGN.md) - 多租户架构
13. [REDIS_DESIGN.md](docs/REDIS_DESIGN.md) - 分布式锁设计
14. [ADVANCED_FEATURES.md](docs/ADVANCED_FEATURES.md) - 高级功能说明

### 运维指南
15. [DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) - 部署指南
16. [BUGFIX_GUIDE.md](docs/BUGFIX_GUIDE.md) - Bug修复指南

### SQL资源
17. [schema.sql](docs/schema.sql) - 完整数据库Schema
18. [init-data.sql](docs/init-data.sql) - 初始化数据

---

## 🤝 贡献指南

### 如何贡献

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

### 代码规范

- 遵循 Google Java Style Guide
- 所有公共方法必须有JavaDoc注释
- 单元测试覆盖率不低于80%
- 使用 Lombok 简化 getter/setter
- 使用 @Transactional 管理事务

### 报告问题

如发现 Bug 或有建议，请通过 Issues 提出：
- 详细描述问题现象
- 提供复现步骤
- 附加相关日志和错误截图

---

## 📖 学习资源

### 推荐阅读

- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [MyBatis Plus 官方文档](https://baomidou.com/)
- [Redisson 官方文档](https://github.com/redisson/redisson)
- [分布式系统设计] - 理论基础

### 相关项目

- [XXL-Job](https://github.com/xuxueli/xxl-job) - 轻量级分布式任务调度
- [Apache Airflow](https://github.com/apache/airflow) - 工作流编排平台
- [Kubernetes](https://github.com/kubernetes/kubernetes) - 容器编排

---

## 📝 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

---

## 📞 联系方式

- 📧 Email: support@example.com
- 💬 Issues: [GitHub Issues](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/issues)
- 📖 Wiki: [GitHub Wiki](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/wiki)

---

## 🙏 致谢

感谢所有贡献者和使用本项目的用户。本项目的设计灵感来自于：

- XXL-Job 的简洁设计
- Kubernetes 的资源调度理念
- Apache Airflow 的工作流概念

---

**🎉 祝你使用本项目愉快！如有任何问题，欢迎通过Issues反馈。**

---

**最后更新**: 2026年4月11日 | **当前版本**: V1.0.0-SNAPSHOT
