package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceQuotaDetailResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateResourceQuotaRequest;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

/**
 * 租户资源配额（P2-3）。
 */
public interface ResourceQuotaService {

    Result<ResourceQuotaDetailResponse> getQuota(long tenantId);

    Result<ResourceQuotaDetailResponse> updateQuota(long tenantId, UpdateResourceQuotaRequest request);

    Result<QuotaCheckResponse> checkQuota(long tenantId, QuotaCheckRequest request);

    /**
     * 已鉴权链路内预校验（如 reserve），不重复做登录校验。
     */
    QuotaCheckResponse evaluateReserveFeasibility(long tenantId, int cpu, int memoryMb, int gpu);

    /**
     * 预留成功后原子增加已用量；返回 false 表示配额行不存在或并发下已满（调用方应回滚事务）。
     */
    boolean tryConsumeForReserve(long tenantId, int cpu, int memoryMb, int gpu);

    /**
     * 释放预留后原子回退已用量；失败抛出以触发事务回滚。
     */
    void releaseForReserve(long tenantId, int cpu, int memoryMb, int gpu);
}
