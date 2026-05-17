package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTenantRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Tenant;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.service.TenantService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "租户", description = "租户创建与成员管理")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "创建租户")
    @PostMapping("/create")
    public Result<Tenant> createTenant(@RequestBody @Valid CreateTenantRequest request) {
        return tenantService.createTenant(request);
    }

    @Operation(summary = "查询租户成员列表")
    @GetMapping("/{tenantId}/members")
    public Result<List<TenantMember>> getTenantMembers(
            @Parameter(description = "租户 ID") @PathVariable("tenantId") Long tenantId) {
        return tenantService.getTenantMembers(tenantId);
    }

    @Operation(summary = "添加租户成员")
    @PostMapping("/{tenantId}/members")
    public Result<TenantMember> addTenantMember(
            @Parameter(description = "租户 ID") @PathVariable("tenantId") Long tenantId,
            @Parameter(description = "用户 ID") @RequestParam Long userId,
            @Parameter(description = "角色：OWNER/ADMIN/MEMBER/GUEST") @RequestParam String role) {
        return tenantService.addTenantMember(tenantId, userId, role);
    }

    @Operation(summary = "更新成员角色")
    @PutMapping("/{tenantId}/members/{userId}/role")
    public Result<TenantMember> updateTenantMemberRole(
            @PathVariable("tenantId") Long tenantId,
            @PathVariable("userId") Long userId,
            @RequestParam String role) {
        return tenantService.updateTenantMemberRole(tenantId, userId, role);
    }

    @Operation(summary = "移除租户成员")
    @DeleteMapping("/{tenantId}/members/{userId}")
    public Result<Void> deleteTenantMember(
            @PathVariable("tenantId") Long tenantId,
            @PathVariable("userId") Long userId) {
        return tenantService.deleteTenantMember(tenantId, userId);
    }
}
