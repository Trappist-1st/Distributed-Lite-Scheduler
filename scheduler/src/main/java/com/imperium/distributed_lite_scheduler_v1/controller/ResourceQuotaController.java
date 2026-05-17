package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceQuotaDetailResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateResourceQuotaRequest;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户资源配额 API（P2-3，设计稿 §4）。
 */
@RestController
@RequestMapping("/api/resource/quota")
public class ResourceQuotaController {

    private final ResourceQuotaService resourceQuotaService;

    public ResourceQuotaController(ResourceQuotaService resourceQuotaService) {
        this.resourceQuotaService = resourceQuotaService;
    }

    /**
     * 获取租户资源配额详情。
     * @param tenantId
     * @return
     */
    @GetMapping("/{tenantId}")
    public Result<ResourceQuotaDetailResponse> getQuota(@PathVariable Long tenantId) {
        return resourceQuotaService.getQuota(tenantId);
    }

    /**
     * 更新租户资源配额。
     * @param tenantId
     * @param request
     * @return
     */
    @PutMapping("/{tenantId}")
    public Result<ResourceQuotaDetailResponse> updateQuota(
            @PathVariable Long tenantId,
            @RequestBody @Valid UpdateResourceQuotaRequest request) {
        return resourceQuotaService.updateQuota(tenantId, request);
    }

    /**
     * 检查租户资源配额是否满足请求。
     * @param tenantId
     * @param request
     * @return
     */
    @PostMapping("/{tenantId}/check")
    public Result<QuotaCheckResponse> checkQuota(
            @PathVariable Long tenantId,
            @RequestBody @Valid QuotaCheckRequest request) {
        return resourceQuotaService.checkQuota(tenantId, request);
    }
}
