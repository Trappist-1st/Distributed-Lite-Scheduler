package com.imperium.distributed_lite_scheduler_v1.service.workflow.annotation;

import java.lang.annotation.*;

/**
 * 工作流权限检查注解
 * 
 * 用于标记需要权限检查的方法
 * 该注解与AOP结合使用，在方法执行前自动进行权限验证
 * 
 * 示例：
 * @RequireWorkflowPermission(roles = {"OWNER", "ADMIN"}, message = "当前角色无操作权限")
 * public void updateWorkflow(...) { }
 * 
 * @author UNSC
 * @since 2026-05-12
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireWorkflowPermission {

    /**
     * 允许的角色集合
     */
    String[] roles() default {};

    /**
     * 权限不足时的错误消息
     */
    String message() default "当前角色无权限执行此操作";

    /**
     * 为 true 时，在鉴权通过后校验首参数对应的项目属于当前租户：
     * {@link com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowCreateRequest#getProjectId()}；
     * 或方法名为 {@code listWorkflows} 时首参数 {@code Long projectId}。
     */
    boolean validateProject() default false;
}
