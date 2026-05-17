package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceQuotaDetailResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateResourceQuotaRequest;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "资源配额", description = "租户级 CPU/内存/GPU 配额管理与校验")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/resource/quota")
public class ResourceQuotaController {

    private final ResourceQuotaService resourceQuotaService;

    public ResourceQuotaController(ResourceQuotaService resourceQuotaService) {
        this.resourceQuotaService = resourceQuotaService;
    }

    @Operation(summary = "查询租户资源配额")
    @GetMapping("/{tenantId}")
    public Result<ResourceQuotaDetailResponse> getQuota(
            @Parameter(description = "租户 ID") @PathVariable Long tenantId) {
        return resourceQuotaService.getQuota(tenantId);
    }

    @Operation(summary = "更新租户资源配额")
    @PutMapping("/{tenantId}")
    public Result<ResourceQuotaDetailResponse> updateQuota(
            @PathVariable Long tenantId,
            @RequestBody @Valid UpdateResourceQuotaRequest request) {
        return resourceQuotaService.updateQuota(tenantId, request);
    }

    @Operation(summary = "校验配额是否满足", description = "调度前或提交任务前预检查")
    @PostMapping("/{tenantId}/check")
    public Result<QuotaCheckResponse> checkQuota(
            @PathVariable Long tenantId,
            @RequestBody @Valid QuotaCheckRequest request) {
        return resourceQuotaService.checkQuota(tenantId, request);
    }
}
