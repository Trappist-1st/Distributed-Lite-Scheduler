# 工作流服务 AOP 权限检查框架使用指南

## 概述
本文档说明了如何使用 `@RequireWorkflowPermission` 注解和 `WorkflowSecurityAspect` AOP 切面来实现工作流服务的权限检查。

---

## 核心组件

### 1. @RequireWorkflowPermission 注解
位置: `service.workflow.annotation.RequireWorkflowPermission`

**职责**：
- 声明式地定义方法所需的权限角色
- 为 AOP 切面提供权限检查的元数据
- 支持自定义错误消息

**属性**：
```java
@interface RequireWorkflowPermission {
    // 允许的角色集合
    String[] roles() default {};
    
    // 权限不足时的错误消息
    String message() default "当前角色无权限执行此操作";
    
    // 是否需要验证项目存在性（扩展用）
    boolean validateProject() default true;
}
```

### 2. WorkflowSecurityAspect AOP 切面
位置: `service.workflow.aspect.WorkflowSecurityAspect`

**职责**：
- 拦截带有 `@RequireWorkflowPermission` 注解的方法
- 在方法执行前进行权限验证
- 记录操作日志和性能指标
- 统一处理异常

**核心通知**：
1. **@Around - 权限检查和执行**（主要逻辑）
   - 提取方法注解中的权限信息
   - 调用 TenantAccessGuard 进行权限验证
   - 如果权限检查失败，抛出 IllegalArgumentException
   - 如果通过，执行目标方法，并记录耗时

2. **@AfterThrowing - 异常处理**（辅助）
   - 捕捉工作流服务中的所有异常
   - 按类型进行分类记录和处理

---

## 使用示例

### 示例 1：创建工作流（需要权限检查）
```java
@Override
@Transactional
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN", "MEMBER"},
    message = "当前角色无创建工作流权限",
    validateProject = true
)
public Long createWorkflow(WorkflowCreateRequest request) {
    // 方法体
    // AOP 会在此之前进行权限检查
    // 权限检查通过后，才会执行这个方法
}
```

### 示例 2：删除工作流（需要更高的权限）
```java
@Override
@Transactional
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN"},
    message = "当前角色无删除工作流权限"
)
public void deleteWorkflow(Long id) {
    // 只有 OWNER 和 ADMIN 可以删除
}
```

### 示例 3：查看工作流（权限最低）
```java
@Override
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN", "MEMBER", "GUEST"},
    message = "当前角色无查看工作流权限"
)
public Workflow getById(Long id) {
    // 所有角色都可以查看
}
```

---

## 执行流程

### 正常执行流程
```
请求到达
     ↓
AOP @Around 拦截
     ↓
提取 @RequireWorkflowPermission 注解
     ↓
调用 TenantAccessGuard.requireTenantMember()
     ↓
权限检查通过？
     ↓ 是
记录日志（执行信息）
     ↓
执行目标方法
     ↓
返回结果并记录耗时
     ↓
返回给调用者
```

### 权限检查失败流程
```
请求到达
     ↓
AOP @Around 拦截
     ↓
提取 @RequireWorkflowPermission 注解
     ↓
调用 TenantAccessGuard.requireTenantMember()
     ↓
权限检查通过？
     ↓ 否
记录警告日志
     ↓
抛出 IllegalArgumentException
     ↓
AOP @AfterThrowing 捕捉异常
     ↓
记录异常日志
     ↓
异常传播给调用者
```

---

## 日志输出示例

### 成功执行
```
2026-05-12 10:30:00.123 [INFO]  执行权限检查 - 方法: createWorkflow 所需角色: OWNER,ADMIN,MEMBER
2026-05-12 10:30:00.145 [DEBUG] 权限验证通过：用户角色 ADMIN，租户 12345
2026-05-12 10:30:01.234 [INFO]  方法执行成功 - 方法: createWorkflow 耗时: 1089ms
```

### 权限检查失败
```
2026-05-12 10:30:00.123 [INFO]  执行权限检查 - 方法: deleteWorkflow 所需角色: OWNER,ADMIN
2026-05-12 10:30:00.145 [WARN] 权限检查失败 - 方法: deleteWorkflow 原因: 当前角色为 MEMBER，无权执行此操作
2026-05-12 10:30:00.150 [WARN] 工作流操作验证失败: 当前角色为 MEMBER，无权执行此操作
```

---

## 当前应用的方法

以下是 `WorkflowServiceImpl` 中已应用 `@RequireWorkflowPermission` 的方法：

| 方法 | 所需角色 | 说明 |
|------|---------|------|
| createWorkflow | OWNER, ADMIN, MEMBER | 创建工作流 |
| updateWorkflow | OWNER, ADMIN, MEMBER | 更新工作流 |
| deleteWorkflow | OWNER, ADMIN | 删除工作流（最严格） |
| getById | OWNER, ADMIN, MEMBER, GUEST | 获取单个工作流 |
| listWorkflows | OWNER, ADMIN, MEMBER, GUEST | 获取工作流列表 |

---

## 最佳实践

### 1. 注解放置位置
```java
// ❌ 错误：注解应该在 @Transactional 之后
@RequireWorkflowPermission(...)
@Transactional
public void updateWorkflow(...) { }

// ✅ 正确：@Transactional 在前，@RequireWorkflowPermission 在后
@Transactional
@RequireWorkflowPermission(...)
public void updateWorkflow(...) { }
```

### 2. 角色定义
```java
// ✅ 推荐：明确定义所需的最小权限集合
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN"},  // 只有这两个角色可以执行
    message = "..."
)

// ❌ 避免：注解中放置所有角色
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN", "MEMBER", "GUEST"},  // 不精确
    message = "..."
)
```

### 3. 错误消息
```java
// ✅ 推荐：清晰、用户友好的错误消息
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN"},
    message = "只有项目所有者和管理员可以删除工作流"
)

// ❌ 避免：模糊的错误消息
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN"},
    message = "权限不足"  // 没有说明原因
)
```

---

## 性能考虑

### AOP 的性能开销
- **权限检查成本**：~1-5ms（取决于 TenantAccessGuard 的实现）
- **日志记录成本**：~0.5-2ms
- **总体开销**：~1-7ms 每次方法调用

### 优化建议
1. **缓存权限信息**：在 TenantAccessGuard 中添加权限缓存
2. **异步日志**：将日志记录改为异步，减少对业务逻辑的影响
3. **采样日志**：对频繁调用的方法进行日志采样

---

## 扩展场景

### 场景 1：添加新的 CRUD 方法
当添加新的 CRUD 方法时，只需添加注解：
```java
@Override
@Transactional
@RequireWorkflowPermission(
    roles = {"OWNER", "ADMIN"},
    message = "当前角色无权执行此操作"
)
public void newOperation(Long id, Request request) {
    // 实现业务逻辑
}
```

### 场景 2：不同的权限粒度
可以为不同操作定义不同的权限粒度：
```java
// 严格权限
@RequireWorkflowPermission(roles = {"OWNER"})
public void deleteWorkflow(Long id) { }

// 中等权限
@RequireWorkflowPermission(roles = {"OWNER", "ADMIN"})
public void updateWorkflow(Long id, Request request) { }

// 宽松权限
@RequireWorkflowPermission(roles = {"OWNER", "ADMIN", "MEMBER", "GUEST"})
public Workflow getById(Long id) { }
```

### 场景 3：条件权限检查
如果需要根据不同条件进行权限检查，可以扩展 AOP 逻辑：
```java
// 可以在 WorkflowSecurityAspect 中添加更复杂的逻辑
@Around("requireWorkflowPermission()")
public Object checkPermissionAndExecute(ProceedingJoinPoint pjp) throws Throwable {
    // 可以根据方法参数进行更复杂的权限检查
    // 例如：检查用户是否是项目所有者
}
```

---

## 故障排查

### 问题 1：AOP 不生效（权限检查没有执行）
**可能原因**：
- 未在方法上添加 `@RequireWorkflowPermission` 注解
- 注解未被正确导入
- AOP 代理配置有问题

**解决方案**：
```java
// 1. 确保注解已导入
import com.imperium.distributed_lite_scheduler_v1.service.workflow.annotation.RequireWorkflowPermission;

// 2. 确保注解正确添加到方法上
@RequireWorkflowPermission(...)
public void method(...) { }

// 3. 在 application.yaml 中启用 AOP
spring:
  aop:
    auto: true
    proxy-target-class: true  # 使用 CGLIB 代理
```

### 问题 2：性能下降
**可能原因**：
- 权限检查耗时过长
- 日志记录过于频繁
- TenantAccessGuard 查询数据库

**解决方案**：
```java
// 在 TenantAccessGuard 中添加缓存
@Cacheable(value = "tenant_roles", key = "#userId")
public Result<Set<String>> getUserRoles(Long userId) {
    // 获取用户角色（添加缓存）
}
```

### 问题 3：日志过多
**解决方案**：
```java
// 在 application.yaml 中调整日志级别
logging:
  level:
    com.imperium.distributed_lite_scheduler_v1.service.workflow.aspect: WARN
```

---

## 总结

通过 `@RequireWorkflowPermission` 注解和 `WorkflowSecurityAspect` AOP 切面的组合，我们实现了：

✅ **集中化权限管理** - 所有权限检查都通过 AOP 统一处理  
✅ **声明式配置** - 使用注解清晰地表达方法所需的权限  
✅ **自动日志记录** - 自动记录操作日志和性能指标  
✅ **统一异常处理** - 所有异常都通过 AOP 进行统一处理  
✅ **易于扩展** - 新增方法只需添加注解即可  

这个架构遵循了关注点分离（Separation of Concerns）的原则，使业务逻辑代码更加清洁和可维护。
