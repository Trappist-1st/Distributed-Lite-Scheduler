package com.imperium.distributed_lite_scheduler_v1.service.workflow.aspect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ProjectMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowCreateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.annotation.RequireWorkflowPermission;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.security.WorkflowSecurityContextHolder;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 工作流安全切面：鉴权、可选项目归属校验、绑定线程上下文。
 */
@Aspect
@Component
@Slf4j
public class WorkflowSecurityAspect {

    private static final int NOT_DELETED = 0;

    @Autowired
    private TenantAccessGuard tenantAccessGuard;

    @Autowired
    private ProjectMapper projectMapper;

    @Pointcut("@annotation(com.imperium.distributed_lite_scheduler_v1.service.workflow.annotation.RequireWorkflowPermission)")
    public void requireWorkflowPermission() {
    }

    @Around("requireWorkflowPermission()")
    public Object checkPermissionAndExecute(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        RequireWorkflowPermission permission = method.getAnnotation(RequireWorkflowPermission.class);

        Set<String> roles = permission.roles().length > 0 ? Set.of(permission.roles()) : Set.of();
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                roles, permission.message());
        if (!access.isSuccess()) {
            log.warn("工作流权限拒绝 method={} reason={}", method.getName(), access.getMessage());
            throw new IllegalArgumentException(access.getMessage());
        }

        TenantAccessGuard.AccessContext ctx = access.getData();
        validateProjectIfNeeded(pjp, method, permission, ctx);

        WorkflowSecurityContextHolder.set(ctx);
        try {
            return pjp.proceed();
        } finally {
            WorkflowSecurityContextHolder.clear();
        }
    }

    private void validateProjectIfNeeded(
            ProceedingJoinPoint pjp,
            Method method,
            RequireWorkflowPermission permission,
            TenantAccessGuard.AccessContext ctx) {
        if (!permission.validateProject()) {
            return;
        }
        Object[] args = pjp.getArgs();
        if (args.length == 0) {
            return;
        }
        Long projectId = null;
        if (args[0] instanceof WorkflowCreateRequest req) {
            projectId = req.getProjectId();
        } else if (args[0] instanceof Long && "listWorkflows".equals(method.getName())) {
            projectId = (Long) args[0];
        }
        if (projectId == null) {
            throw new IllegalArgumentException("项目ID不能为空");
        }
        Long tenantId = ctx.principal().tenantId();
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            throw new IllegalArgumentException("项目不存在");
        }
    }

    @AfterThrowing(
            pointcut = "execution(* com.imperium.distributed_lite_scheduler_v1.service.workflow.impl.WorkflowServiceImpl.*(..))",
            throwing = "exception"
    )
    public void handleWorkflowException(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            log.warn("工作流操作验证失败: {}", exception.getMessage());
        } else if (exception instanceof IllegalStateException) {
            log.error("工作流操作状态异常: {}", exception.getMessage());
        } else {
            log.error("工作流操作出现意外异常", exception);
        }
    }
}
