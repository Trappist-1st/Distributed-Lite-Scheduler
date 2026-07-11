# Distributed Lite Scheduler

| Category | Badges |
|------------|-------------------------------------------------------------------------------------------------------------------------------|
| License | [![License](https://img.shields.io/badge/License-MIT-green)](#license) |
| Language | [![Java](https://img.shields.io/badge/Java-21-blue)](https://www.oracle.com/java/) [![Maven](https://img.shields.io/badge/Maven-3.6%2B-red)](https://maven.apache.org/) |
| Framework | [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)](https://spring.io/projects/spring-boot) [![MyBatis Plus](https://img.shields.io/badge/MyBatis%20Plus-3.5.15-blue)](https://baomidou.com/) |
| Storage | [![MySQL](https://img.shields.io/badge/MySQL-8.0-orange)](https://www.mysql.com/) [![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/) |
| Containers | [![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://www.docker.com/) [![Redisson](https://img.shields.io/badge/Redisson-4.0.0-red)](https://github.com/redisson/redisson) |
| Dev tools | [![Swagger](https://img.shields.io/badge/OpenAPI-Swagger-green)](https://springdoc.org/) [![JWT](https://img.shields.io/badge/JWT-JJWT%200.12.6-blue)](https://github.com/jwtk/jjwt) |

| Module | Status |
|---------|----------------------------------------------------------------------------------------------------------------|
| Scheduler (control plane) | [![Build](https://img.shields.io/badge/build-passing-brightgreen)](scheduler/) |
| Worker (remote executor) | [![Build](https://img.shields.io/badge/build-passing-brightgreen)](worker/) |

---

[Distributed Lite Scheduler](#) (or simply **DLS**) is a platform to programmatically author, schedule, and monitor resource-aware workflows for small and medium-sized teams.

When workflows are defined as code, they become more maintainable, versionable, testable, and collaborative. DLS extends the classic task-scheduler pattern with **resource-aware scheduling**, **multi-tenancy**, and **DAG workflow orchestration** — combining the simplicity of XXL-Job, the resource-management concepts of Kubernetes, and the DAG model of Apache Airflow in a lightweight Spring Boot package.

The DLS scheduler executes your tasks on an array of remote workers while following the specified dependencies and respecting CPU / memory / GPU quotas per tenant. Rich REST APIs and Swagger UI make performing complex surgeries on workflows a snap. The pluggable executor model (Shell / Python / Docker / HTTP) makes it easy to integrate with almost any existing data, dev, or AI pipeline.

---

## Requirements

Distributed Lite Scheduler is tested with:

| | Scheduler | Worker |
|------------|----------------------------------|----------------------------------|
| Java | 21 | 21 |
| Spring Boot | 4.0.5 | 4.0.5 |
| Maven | 3.6+ | 3.6+ |
| MySQL | 8.0 | — |
| Redis | 7.0+ | — |
| Platform | AMD64 / ARM64 (Linux, macOS, Windows) | AMD64 / ARM64 (Linux, macOS, Windows) |
| Docker | Optional (recommended for deployment) | Optional (recommended for deployment) |

**Note**: MySQL is the only metadata store tested in CI. PostgreSQL is on the roadmap but not yet verified.

**Note**: The worker runs subprocesses (`shell`, `python`) and spawns Docker containers (`docker`) on the host it is deployed on. In production you should only run workers on Linux-based distros and isolate them via containers or namespaces.

---

## Getting started

Visit the official documentation in the [`docs/`](docs) folder for help with [installing DLS](#installation), [getting started](#quick-start), or walking through a more complete [tutorial](docs/PROJECT_PLAN.md).

> Note: If you're looking for the latest design documents (development branch), you can find them in [docs/](docs).

For more information on rollout designs and per-phase implementation plans, visit the [Project Plan](docs/PROJECT_PLAN.md) and the dedicated design documents under `docs/P*_*_DESIGN.md`.

Documentation for dependent modules — the remote worker, Docker image, Docker Compose stack — you'll find in [docker-compose.yml](docker-compose.yml), [scheduler/Dockerfile](scheduler/Dockerfile), and [worker/Dockerfile](worker/Dockerfile).

---

## Installation

For comprehensive instructions on setting up your local development environment, please refer to this section. The fastest path is via Docker Compose, which brings up MySQL, Redis, the scheduler, and a worker in a single command.

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/Distributed-Lite-Scheduler-V1.git
cd Distributed-Lite-Scheduler-V1
```

### 2. Configure environment

Copy `.env.example` to `.env` and adjust credentials:

```bash
cp .env.example .env
# edit .env: JWT_SECRET, INTERNAL_API_TOKEN, WORKER_API_TOKEN, DB credentials
```

### 3. Start the stack

```bash
# Bring up MySQL + Redis + scheduler + worker (auto-creates schema & init data)
docker compose up -d --build

# Scale workers horizontally
docker compose up -d --scale worker=3
```

The scheduler is reachable at <http://localhost:8080>, the worker at <http://localhost:9090>.

### 4. Verify the install

```bash
# Scheduler health
curl http://localhost:8080/actuator/health
# {"status":"UP"}

# OpenAPI / Swagger UI
# http://localhost:8080/swagger-ui.html
```

---

## Quick start (without Docker)

If you prefer to run the scheduler and worker directly with Maven / an IDE:

1. Start MySQL 8.0 and Redis 7 locally.
2. Initialize the schema:

   ```bash
   mysql -u root -p < docs/schema-complete.sql
   mysql -u root -p < docs/init-data.sql
   ```

3. Build the multi-module project:

   ```bash
   mvn clean install -DskipTests
   ```

4. Run the scheduler (control plane):

   ```bash
   cd scheduler
   mvn spring-boot:run
   # or run DistributedLiteSchedulerV1Application from your IDE
   ```

5. Run a worker (remote executor):

   ```bash
   cd worker
   mvn spring-boot:run
   # or run DistributedLiteWorkerApplication from your IDE
   ```

6. Open Swagger UI at <http://localhost:8080/swagger-ui.html> and submit your first task via `POST /api/task/submit`.

---

## Official source code

This repository is the canonical source of Distributed Lite Scheduler. Released versions:

- Are tagged on the main branch following [SemVer](https://semver.org/).
- Can be downloaded from the [releases page](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/releases).
- Are accompanied by a changelog describing notable changes per release.

Following best practices, the source packages released are sufficient for a user to build and test the release provided they have access to the appropriate platform and tools.

---

## Convenience packages

There are other ways of installing and using DLS. Those are "convenience" methods — they are not the only way to run the platform, but they are handy for users who do not want to build the software themselves.

- [Docker Compose stack](docker-compose.yml) to bring up a complete local cluster with one command.
- [Scheduler Dockerfile](scheduler/Dockerfile) / [Worker Dockerfile](worker/Dockerfile) to build standalone container images.
- Pre-built images (planned) published to a public registry once the first release is cut.

All these artifacts are built from the same `pom.xml` and Dockerfiles kept in this repository.

---

## Architecture

DLS is split into two Maven modules — a **scheduler** (control plane) and a **worker** (remote executor) — that communicate over REST and Redis Streams.

```
┌─────────────────────────────────────────────────────────────┐
│                REST API / Swagger UI / CLI / OpenAPI         │
│        [任务管理] [资源监控] [工作流编排] [租户与配额]          │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                   Scheduler (control plane)                  │
│  • Leader election (Redisson)    • DAG parsing & topo sort   │
│  • Resource-aware scheduling     • Task state machine        │
│  • Multi-tenant quota guard      • Task dispatch & retry     │
└──────────────────────┬──────────────────────────────────────┘
                       │  REST + Redis Streams
        ┌──────────────┴──────────────┐
        │                             │
┌───────▼──────────────┐   ┌──────────▼──────────────────────┐
│ Worker (executor)    │   │ Worker (executor)                │
│ • Heartbeat / reg    │   │ • Heartbeat / reg                │
│ • Shell / Python     │   │ • Docker / HTTP                  │
│ • Status callback    │   │ • Status callback                │
└──────────────────────┘   └──────────────────────────────────┘
        │                             │
        └──────────────┬──────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
│ MySQL 8.0    │ │ Redis 7  │ │ MinIO / OSS │
│ 元数据存储     │ │ 锁/队列   │ │ 日志归档      │
└──────────────┘ └──────────┘ └─────────────┘
```

### Core design principles

1. **High availability** — multiple scheduler instances, leader elected via a Redis distributed lock.
2. **High reliability** — task persistence, retry on failure, idempotent state transitions, watchdog auto-renewal for locks.
3. **High efficiency** — async dispatch, batched scans, Redis Stream event-driven completion handling, resource reservation.
4. **Easy to operate** — structured logs, OpenAPI surface, health & metrics endpoints, single-command bring-up.

---

## Features

### Resource-aware scheduling

The defining feature of DLS: it schedules **resources**, not just **tasks**.

```
Traditional scheduler (e.g. XXL-Job):
  Task A (CPU 4) → Node 1 (already running 8 tasks × 2 cores)
  → memory pressure, task stalls ❌

Distributed Lite Scheduler:
  Task A needs { cpu: 4, memoryMb: 8192, gpu: 0 }
  → quota check ✅ → find available node ✅ → reserve resource ✅ → dispatch ✅
```

Managed heterogeneous resources: **CPU cores**, **memory (MB / GB)**, **GPU cards + GPU model**.

### Multi-tenancy

Each tenant has its own resource quotas, task namespace, cost accounting, and audit log — ensuring fair allocation and preventing noisy neighbors from monopolizing the cluster.

### DAG workflow orchestration

```
    ┌─→ Task B
Task A ─┤
    └─→ Task C ─→ Task D
```

- Topological sort with automatic dependency resolution.
- Conditional and dynamic branching (`P4-4_CONDITIONAL_BRANCH_SUPPORT_DESIGN`).
- Per-task retry, manual rerun of failed tasks, layer-level progress monitoring.

### Pluggable executors

| Executor | Runs on | Use case |
|------------|-----------|-----------|
| `shell` | Worker | Shell scripts, ops jobs |
| `python` | Worker | Data processing, ML scripts |
| `docker` | Worker | Containerized, fully isolated runs |
| `http` | Scheduler | Remote call to an external service |
| `java` (extension point) | Scheduler | In-process Java method invocation |

### Distributed consistency

- **Redis distributed locks (Redisson)** — scheduler leader election, per-task schedule mutex, resource-allocation mutex, with watchdog auto-renewal.
- **Database optimistic locks** — concurrent control on task-state transitions and resource-node availability updates.
- **Idempotency** — task ID de-duplication and idempotent message consumption on the worker callback path.

### Scheduling algorithms

The strategy is selectable via `SCHEDULER_STRATEGY` (`fifo` | `priority` | `resource-aware`):

- **FIFO** — simple first-in-first-out queue (`P3-2`).
- **Priority** — heap-based priority queue, supports task priority (`P3-3`).
- **Resource-aware** — combines quota check, node availability, and backfill to maximize cluster utilization (`P3-4`).

---

## Project structure

```
Distributed-Lite-Scheduler-V1/
├── scheduler/                                # Control plane (Spring Boot)
│   └── src/main/java/com/imperium/distributed_lite_scheduler_v1/
│       ├── DistributedLiteSchedulerV1Application.java
│       ├── config/                            # Spring configs (Security, Mybatis, Async, …)
│       ├── controller/                        # REST controllers
│       ├── service/
│       │   ├── scheduler/                     # Leader election, scheduling services
│       │   ├── workflow/                      # DAG parsing, instance execution, stream
│       │   ├── executor/                      # Pluggable task-type executors
│       │   └── impl/                          # Service implementations
│       ├── mapper/                            # MyBatis Plus mappers
│       ├── model/                             # Entities + DTOs (workflow / resource / task)
│       ├── security/                          # JWT, tenant access guard
│       ├── exception/                         # Global exception handling
│       └── utils/                             # Result wrappers, UUID, encryption
│
├── worker/                                    # Remote executor (Spring Boot)
│   └── src/main/java/com/imperium/distributed_lite_worker/
│       ├── DistributedLiteWorkerApplication.java
│       ├── bootstrap/                         # Registration + heartbeat
│       ├── controller/                        # Worker run endpoint
│       ├── executor/                          # Shell / Python / Docker executors
│       ├── runtime/                           # Running task registry, process handles
│       ├── service/                           # Scheduler node + callback clients
│       └── security/                          # Worker token filter
│
├── docs/                                      # Design documents (60+ files)
├── tools/                                     # Helper tooling (e.g. Redis scripts)
├── docker-compose.yml                         # Full local stack
├── scheduler/Dockerfile  /  worker/Dockerfile
├── pom.xml                                    # Maven parent (modules: scheduler, worker)
├── .env.example                               # Environment template
└── README.md                                  # This file
```

---

## REST API surface

A quick map of the most-used endpoints (full schema in Swagger UI):

| Module | Method | Endpoint | Purpose |
|------------|--------|-----------------------------|----------------------------|
| Auth | POST | `/api/auth/login` | JWT login |
| Tenant | POST | `/api/tenant` | Create tenant |
| Project | POST | `/api/project` | Create project |
| Task | POST | `/api/task` | Create task definition |
| Task submit | POST | `/api/task/submit` | Submit a task instance |
| Task instance | GET | `/api/task-instance/{id}` | Query instance status |
| Resource | POST | `/api/resource/register` | Register a worker node |
| Resource | POST | `/api/resource/reserve` | Reserve resources for a task |
| Resource quota | POST | `/api/resource-quota/check` | Pre-check tenant quota |
| Workflow | POST | `/api/workflow` | Create a DAG workflow |
| Workflow execution | POST | `/api/workflow/{id}/run` | Trigger a workflow run |
| Workflow instance | GET | `/api/workflow-instance/{id}` | Inspect a workflow run |
| Workflow monitor | GET | `/api/workflow-instance/{id}/progress` | Layer-level progress |

Example — submit a task:

```bash
POST /api/task/submit
{
  "tenantId": 1,
  "projectId": 1,
  "taskName": "etl-daily",
  "resourceRequirement": { "cpu": 2, "memoryMb": 4096, "gpu": 0 },
  "executor": { "type": "shell", "command": "bash run-etl.sh" }
}
```

---

## Configuration

The scheduler reads its configuration from `scheduler/src/main/resources/application.yaml` and any `SPRING_*` / `SCHEDULER_*` / `WORKER_*` environment variables. The most important knobs:

| Env var | Default | Meaning |
|---------|-------------------------|-----------------------------------------|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/...` | Metadata DB JDBC URL |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis for locks, streams, cache |
| `JWT_SECRET` | (dev only) | HS256 signing key (≥ 32 bytes) |
| `INTERNAL_API_TOKEN` | — | Token for internal service-to-service calls |
| `SCHEDULER_STRATEGY` | `resource-aware` | `fifo` \| `priority` \| `resource-aware` |
| `SCHEDULER_LEADER_LOCK_KEY` | `scheduler:leader:lock` | Redis key for leader election |
| `TASK_EXECUTOR_MODE` | `in-process` | `in-process` \| `remote` (dispatch to workers) |
| `WORKER_API_TOKEN` | — | Shared token between scheduler and workers |
| `WORKER_NODE_NAME` | `worker-docker-1` | Worker registration name |
| `WORKER_TOTAL_CPU` / `WORKER_TOTAL_MEMORY_MB` | `4` / `8192` | Worker advertised capacity |

---

## Semantic versioning

As of the first tagged release, DLS follows a strict [SemVer](https://semver.org/) approach:

- **MAJOR** — incompatible API or schema changes.
- **MINOR** — new features, backward-compatible.
- **PATCH** — bug fixes and documentation-only changes.

Schema migrations and DAG/DTO contracts are considered part of the public API; breaking changes there bump the MAJOR version.

---

## Version life cycle

| Version | Current patch | State | First release | Limited maintenance | EOL |
|-----------|------------------|----------|-----------------|------------------------|------|
| 1.x | 0.0.1-SNAPSHOT | Development | TBD | TBD | TBD |

Limited-support versions receive only security and critical bug fixes. EOL versions receive no fixes or support. We always recommend running the latest available patch release.

---

## Roadmap

### Phase 1 — Core platform (done)

- [x] System architecture & database design
- [x] User authentication & authorization (JWT)
- [x] Multi-tenant isolation framework
- [x] Project management module
- [x] Task definition & instance lifecycle

### Phase 2 — Resource management (done)

- [x] **P2-1** Resource node management — worker registration, heartbeat, health check
- [x] **P2-2** Resource slot management — reserve / release / track
- [x] **P2-3** Tenant resource quota — quotas, pre-check, accounting

### Phase 3 — Scheduling engine (done)

- [x] **P3-1** Task submit queue
- [x] **P3-2** FIFO scheduler
- [x] **P3-3** Priority scheduler
- [x] **P3-4** Resource-aware scheduler
- [x] **P3-5** Concurrency safety (locks + idempotency)

### Phase 4 — DAG workflow engine (done)

- [x] **P4-1** Workflow definition & parsing
- [x] **P4-2** Topological sort engine
- [x] **P4-3** Workflow instance execution
- [x] **P4-4** Conditional branch support

### Phase 5 — Operations & extensions (planned)

- [ ] Visualization dashboard
- [ ] Real-time alerting
- [ ] Metrics collection & log analysis
- [ ] API rate limiting & circuit breaking
- [ ] Task tracing across the scheduler → worker hop

See [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) and [docs/项目未来完善方向.md](docs/项目未来完善方向.md) for the full roadmap.

---

## Documentation

The project ships with an extensive design-document set under [`docs/`](docs). Suggested reading order:

### Getting started
1. This README — project overview
2. [PROJECT_PLAN.md](docs/PROJECT_PLAN.md) — project plan
3. [Project_Overview.md](docs/Project_Overview.md) — high-level overview

### Architecture & design
4. [DATABASE_DESIGN.md](docs/DATABASE_DESIGN.md) — full database design
5. [ER_DIAGRAM.md](docs/ER_DIAGRAM.md) — ER diagram
6. [ENTITY_CLASSES.md](docs/ENTITY_CLASSES.md) — entity classes
7. [BUSINESS_LOGIC.md](docs/BUSINESS_LOGIC.md) — business logic walkthrough

### Resource management (core)
8. [THREE_RESOURCE_COMPONENTS_GUIDE.md](docs/THREE_RESOURCE_COMPONENTS_GUIDE.md) — the three resource components (recommended first)
9. [RESOURCE_NODE_MANAGEMENT_ROLLOUT_DESIGN.md](docs/RESOURCE_NODE_MANAGEMENT_ROLLOUT_DESIGN.md)
10. [RESOURCE_SLOT_MANAGEMENT_ROLLOUT_DESIGN.md](docs/RESOURCE_SLOT_MANAGEMENT_ROLLOUT_DESIGN.md)
11. [TENANT_RESOURCE_QUOTA_ROLLOUT_DESIGN.md](docs/TENANT_RESOURCE_QUOTA_ROLLOUT_DESIGN.md)

### Scheduling engine
12. [P3-2_FIFO_SCHEDULER_DESIGN.md](docs/P3-2_FIFO_SCHEDULER_DESIGN.md)
13. [P3-3_PRIORITY_SCHEDULER_DESIGN.md](docs/P3-3_PRIORITY_SCHEDULER_DESIGN.md)
14. [P3-4_RESOURCE_AWARE_SCHEDULER_DESIGN.md](docs/P3-4_RESOURCE_AWARE_SCHEDULER_DESIGN.md)
15. [P3-5_CONCURRENCY_SAFETY_DESIGN.md](docs/P3-5_CONCURRENCY_SAFETY_DESIGN.md)

### DAG workflow engine
16. [P4_DAG_WORKFLOW_ENGINE_DESIGN.md](docs/P4_DAG_WORKFLOW_ENGINE_DESIGN.md)
17. [P4-1_WORKFLOW_DEFINITION_AND_PARSING_DESIGN.md](docs/P4-1_WORKFLOW_DEFINITION_AND_PARSING_DESIGN.md)
18. [P4-2_TOPOLOGICAL_SORT_ENGINE_DESIGN.md](docs/P4-2_TOPOLOGICAL_SORT_ENGINE_DESIGN.md)
19. [P4-3_WORKFLOW_INSTANCE_EXECUTION_DESIGN.md](docs/P4-3_WORKFLOW_INSTANCE_EXECUTION_DESIGN.md)
20. [P4-4_CONDITIONAL_BRANCH_SUPPORT_DESIGN.md](docs/P4-4_CONDITIONAL_BRANCH_SUPPORT_DESIGN.md)

### Reliability & runtime
21. [REDIS_DESIGN.md](docs/REDIS_DESIGN.md) — distributed lock design
22. [REDIS_STREAM_EVENT_DRIVEN_UPGRADE.md](docs/REDIS_STREAM_EVENT_DRIVEN_UPGRADE.md)
23. [CONCURRENCY_CONTROL_GUIDE.md](docs/CONCURRENCY_CONTROL_GUIDE.md)
24. [TASK_STATE_MACHINE_ROLLOUT_DESIGN.md](docs/TASK_STATE_MACHINE_ROLLOUT_DESIGN.md)

### Worker integration
25. [WORKER_CALLBACK_DOCS_NAVIGATION.md](docs/WORKER_CALLBACK_DOCS_NAVIGATION.md)
26. [WORKER_CALLBACK_FINAL_STATE_MAPPING.md](docs/WORKER_CALLBACK_FINAL_STATE_MAPPING.md)

### Operations
27. [DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) — deployment guide
28. [BUGFIX_GUIDE.md](docs/BUGFIX_GUIDE.md) — bug-fix playbook
29. [ADVANCED_FEATURES.md](docs/ADVANCED_FEATURES.md) — advanced features

### SQL resources
30. `docs/schema-complete.sql` — full schema (auto-loaded by Docker Compose)
31. `docs/init-data.sql` — seed data

---

## Contributing

Want to help build Distributed Lite Scheduler? Check out the contribution workflow below.

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/AmazingFeature`).
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

### Code conventions

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
- All public methods must have Javadoc comments.
- Unit test coverage should not be lower than 80%.
- Use Lombok to reduce boilerplate getters/setters.
- Annotate service methods with `@Transactional` where appropriate.
- Keep DTOs and entities in their respective packages under `model/`.

### Reporting issues

If you find a bug or have a suggestion, please open an issue with:

- A clear description of the symptom.
- Steps to reproduce.
- Relevant logs, error messages, or screenshots.
- The scheduler / worker version you are running.

---

## Community standards

Everyone interacting with the Distributed Lite Scheduler project — on GitHub, issues, or any other channel — is expected to follow standard open-source etiquette and be respectful of contributors' time. Abusive behavior, spam, or sustained disruptive conduct may result in PRs being closed and, in extreme cases, reports being filed with GitHub.

---

## Who maintains Distributed Lite Scheduler?

DLS is currently maintained by its original author. The [core committers](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/graphs/contributors) are responsible for reviewing and merging PRs as well as steering conversations around new feature requests. If you would like to become a maintainer, start by consistently contributing high-quality PRs and participating in design discussions under `docs/`.

---

## What goes into the next release?

We follow [SemVer](https://semver.org/):

- **MAJOR** — breaking API or schema changes.
- **MINOR** — new features, backward-compatible.
- **PATCH** — bug fixes and documentation-only changes.

Most of the time, PRs merged to `main` will land in the next `MINOR` release. Bug-fix-only PRs may be cherry-picked to the current `MINOR` branch and released as a `PATCH` — the release manager makes the final cherry-pick decision. Issues are usually not pinned to a milestone; the linked PR is the source of truth for which release shipped a fix.

---

## Related projects

DLS stands on the shoulders of several great open-source projects:

- [Apache Airflow](https://github.com/apache/airflow) — workflow orchestration platform (DAG model inspiration).
- [XXL-Job](https://github.com/xuxueli/xxl-job) — lightweight distributed task scheduler (simplicity inspiration).
- [Kubernetes](https://github.com/kubernetes/kubernetes) — container orchestration (resource-scheduling inspiration).
- [Spring Boot](https://spring.io/projects/spring-boot) — application framework.
- [MyBatis Plus](https://baomidou.com/) — ORM enhancement.
- [Redisson](https://github.com/redisson/redisson) — Redis client with distributed primitives.

---

## Links

- [Documentation](docs/)
- [Project Plan](docs/PROJECT_PLAN.md)
- [Issue tracker](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/issues)
- [Wiki](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/wiki)

---

## License

Distributed Lite Scheduler is released under the **MIT License**. See [LICENSE](LICENSE) for details.

---

## Contact

- Issues: [GitHub Issues](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/issues)
- Wiki: [GitHub Wiki](https://github.com/yourusername/Distributed-Lite-Scheduler-V1/wiki)
- Email: support@example.com

---

## Acknowledgements

Thanks to all contributors and users of this project. The design draws inspiration from:

- The simplicity of **XXL-Job**.
- The resource-scheduling philosophy of **Kubernetes**.
- The workflow concepts of **Apache Airflow**.

---

**Last updated**: 2026-07-11 &nbsp;|&nbsp; **Current version**: 0.0.1-SNAPSHOT
