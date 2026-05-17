package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 切换租户：{@code tenantId} 与 {@code tenantCode} 二选一填写。
 */
public record SwitchTenantRequest(Long tenantId, String tenantCode) {
}
