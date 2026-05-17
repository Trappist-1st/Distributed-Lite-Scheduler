package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTenantRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Tenant;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.service.TenantService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * 创建租户
     * @param request
     * @return
     */
    @PostMapping("/create")
    public Result<Tenant> createTenant(@RequestBody @Valid CreateTenantRequest request) {
        return tenantService.createTenant(request);
    }

    /**
     * 获取租户成员
     * @param tenantId
     * @return
     */
    @GetMapping("/{tenantId}/members")
    public Result<List<TenantMember>> getTenantMembers(@PathVariable("tenantId") Long tenantId) {
        return tenantService.getTenantMembers(tenantId);
    }

    /**
     * 添加租户成员
     * @param tenantId
     * @param userId
     * @param role
     * @return
     */
    @PostMapping("/{tenantId}/members")
    public Result<TenantMember> addTenantMember(
            @PathVariable("tenantId") Long tenantId,
            @RequestParam Long userId,
            @RequestParam String role) {
        return tenantService.addTenantMember(tenantId, userId, role);
    }

    /**
     * 更新租户成员角色
     * @param tenantId
     * @param userId
     * @param role
     * @return
     */
    @PutMapping("/{tenantId}/members/{userId}/role")
    public Result<TenantMember> updateTenantMemberRole(
            @PathVariable("tenantId") Long tenantId,
            @PathVariable("userId") Long userId,
            @RequestParam String role) {
        return tenantService.updateTenantMemberRole(tenantId, userId, role);
    }

    /**
     * 删除租户成员
     * @param tenantId
     * @param userId
     * @return
     */
    @DeleteMapping("/{tenantId}/members/{userId}")
    public Result<Void> deleteTenantMember(
            @PathVariable("tenantId") Long tenantId,
            @PathVariable("userId") Long userId) {
        return tenantService.deleteTenantMember(tenantId, userId);
    }
}
