package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 登录返回中，当前用户在某租户下的可见信息。
 */
public record LoginTenantItem(
        Long tenantId,
        String tenantCode,
        String tenantName,
        String role
) {
}
