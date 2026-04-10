package com.imperium.distributed_lite_scheduler_v1.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTenantRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Tenant;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

import java.util.List;

public interface TenantService extends IService<Tenant> {

    Result<Tenant> createTenant(CreateTenantRequest request);

    Result<List<TenantMember>> getTenantMembers(Long tenantId);

    Result<TenantMember> addTenantMember(Long tenantId, Long userId, String role);

    Result<TenantMember> updateTenantMemberRole(Long tenantId, Long userId, String role);

    Result<Void> deleteTenantMember(Long tenantId, Long userId);
}