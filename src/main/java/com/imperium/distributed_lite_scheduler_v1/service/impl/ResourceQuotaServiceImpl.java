package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.exception.ResourceQuotaInvariantException;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceQuotaMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceQuotaDetailResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateResourceQuotaRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceQuota;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class ResourceQuotaServiceImpl extends ServiceImpl<ResourceQuotaMapper, ResourceQuota> implements ResourceQuotaService {

    private static final Logger log = LoggerFactory.getLogger(ResourceQuotaServiceImpl.class);

    public static final String REJECT_CPU = "CPU_QUOTA_EXCEEDED";
    public static final String REJECT_MEMORY = "MEMORY_QUOTA_EXCEEDED";
    public static final String REJECT_GPU = "GPU_QUOTA_EXCEEDED";
    public static final String REJECT_RUNNING = "RUNNING_TASKS_QUOTA_EXCEEDED";

    private static final Set<String> READ_ROLES = Set.of("OWNER", "ADMIN", "MEMBER", "GUEST");
    private static final Set<String> RESERVE_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> OWNER_ONLY = Set.of("OWNER");

    private static final int DEFAULT_MAX_CPU = 10;
    private static final int DEFAULT_MAX_MEMORY_MB = 10240;
    private static final int DEFAULT_MAX_GPU = 0;
    private static final int DEFAULT_MAX_RUNNING = 50;
    private static final int DEFAULT_MAX_PENDING = 500;

    private final TenantAccessGuard tenantAccessGuard;

    public ResourceQuotaServiceImpl(TenantAccessGuard tenantAccessGuard) {
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @Override
    public Result<ResourceQuotaDetailResponse> getQuota(long tenantId) {
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                READ_ROLES, "当前角色无查看配额权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() != tenantId) {
            return Result.failure(ResultCode.FORBIDDEN, "无权查看其他租户配额");
        }
        ResourceQuota row = loadOrCreateQuota(tenantId);
        return Result.success(toDetail(row));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ResourceQuotaDetailResponse> updateQuota(long tenantId, UpdateResourceQuotaRequest request) {
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                OWNER_ONLY, "仅租户 OWNER 可修改配额");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() != tenantId) {
            return Result.failure(ResultCode.FORBIDDEN, "无权修改其他租户配额");
        }

        ResourceQuota row = loadOrCreateQuota(tenantId);
        // 设计稿 §4.2：新上限不得低于当前已用量，避免立即违约
        if (request.maxCpu() < nz(row.getUsedCpu())) {
            return Result.failure(ResultCode.BAD_REQUEST, "maxCpu 不能小于当前已用 CPU: " + nz(row.getUsedCpu()));
        }
        if (request.maxMemoryMb() < nz(row.getUsedMemoryMb())) {
            return Result.failure(ResultCode.BAD_REQUEST, "maxMemoryMb 不能小于当前已用内存(MB): " + nz(row.getUsedMemoryMb()));
        }
        if (request.maxGpu() < nz(row.getUsedGpu())) {
            return Result.failure(ResultCode.BAD_REQUEST, "maxGpu 不能小于当前已用 GPU: " + nz(row.getUsedGpu()));
        }
        if (request.maxRunningTasks() < nz(row.getRunningTasks())) {
            return Result.failure(ResultCode.BAD_REQUEST, "maxRunningTasks 不能小于当前运行占用计数: " + nz(row.getRunningTasks()));
        }

        row.setMaxCpu(request.maxCpu());
        row.setMaxMemoryMb(request.maxMemoryMb());
        row.setMaxGpu(request.maxGpu());
        row.setMaxRunningTasks(request.maxRunningTasks());
        row.setMaxPendingTasks(request.maxPendingTasks());

        int updated = baseMapper.updateById(row);
        if (updated != 1) {
            return Result.failure(ResultCode.CONFLICT, "配额并发更新冲突，请重试");
        }
        // 设计稿 §4.2：记录审计日志（此处用结构化 INFO，后续可接审计表）
        log.info(
                "resource_quota updated tenantId={} maxCpu={} maxMemoryMb={} maxGpu={} maxRunningTasks={} maxPendingTasks={} operatorUserId={}",
                tenantId, request.maxCpu(), request.maxMemoryMb(), request.maxGpu(), request.maxRunningTasks(), request.maxPendingTasks(),
                access.getData().principal().userId());
        ResourceQuota fresh = baseMapper.selectById(row.getId());
        return Result.success(toDetail(fresh));
    }

    @Override
    public Result<QuotaCheckResponse> checkQuota(long tenantId, QuotaCheckRequest request) {
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                RESERVE_ROLES, "当前角色无配额预检权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() != tenantId) {
            return Result.failure(ResultCode.FORBIDDEN, "无权为其他租户执行配额预检");
        }
        return Result.success(evaluateReserveFeasibility(tenantId, request.requestCpu(), request.requestMemoryMb(), request.requestGpu()));
    }

    @Override
    public QuotaCheckResponse evaluateReserveFeasibility(long tenantId, int cpu, int memoryMb, int gpu) {
        ResourceQuota q = loadOrCreateQuota(tenantId);
        return evaluateAgainstRow(q, cpu, memoryMb, gpu);
    }

    @Override
    public boolean tryConsumeForReserve(long tenantId, int cpu, int memoryMb, int gpu) {
        loadOrCreateQuota(tenantId);
        int rows = baseMapper.consumeIfWithinQuota(tenantId, cpu, memoryMb, gpu, 1);
        return rows == 1;
    }

    @Override
    public void releaseForReserve(long tenantId, int cpu, int memoryMb, int gpu) {
        int rows = baseMapper.releaseUsed(tenantId, cpu, memoryMb, gpu, 1);
        if (rows != 1) {
            throw new ResourceQuotaInvariantException("租户配额回退失败，请排查 resource_quota 与 resource_usage 一致性");
        }
    }

    private QuotaCheckResponse evaluateAgainstRow(ResourceQuota q, int cpu, int memoryMb, int gpu) {
        if (nz(q.getUsedCpu()) + cpu > nz(q.getMaxCpu())) {
            return QuotaCheckResponse.reject(REJECT_CPU);
        }
        if (nz(q.getUsedMemoryMb()) + memoryMb > nz(q.getMaxMemoryMb())) {
            return QuotaCheckResponse.reject(REJECT_MEMORY);
        }
        if (nz(q.getUsedGpu()) + gpu > nz(q.getMaxGpu())) {
            return QuotaCheckResponse.reject(REJECT_GPU);
        }
        if (nz(q.getRunningTasks()) + 1 > nz(q.getMaxRunningTasks())) {
            return QuotaCheckResponse.reject(REJECT_RUNNING);
        }
        return QuotaCheckResponse.ok();
    }

    private ResourceQuota loadOrCreateQuota(long tenantId) {
        ResourceQuota q = baseMapper.selectOne(
                new LambdaQueryWrapper<ResourceQuota>().eq(ResourceQuota::getTenantId, tenantId));
        if (q != null) {
            return q;
        }
        ResourceQuota n = new ResourceQuota();
        n.setTenantId(tenantId);
        n.setMaxCpu(DEFAULT_MAX_CPU);
        n.setMaxMemoryMb(DEFAULT_MAX_MEMORY_MB);
        n.setMaxGpu(DEFAULT_MAX_GPU);
        n.setMaxRunningTasks(DEFAULT_MAX_RUNNING);
        n.setMaxPendingTasks(DEFAULT_MAX_PENDING);
        n.setUsedCpu(0);
        n.setUsedMemoryMb(0);
        n.setUsedGpu(0);
        n.setRunningTasks(0);
        try {
            baseMapper.insert(n);
        } catch (DataIntegrityViolationException e) {
            log.debug("concurrent resource_quota insert for tenant {}", tenantId);
        }
        q = baseMapper.selectOne(new LambdaQueryWrapper<ResourceQuota>().eq(ResourceQuota::getTenantId, tenantId));
        if (q == null) {
            throw new IllegalStateException("无法加载或创建租户配额 tenantId=" + tenantId);
        }
        return q;
    }

    private static ResourceQuotaDetailResponse toDetail(ResourceQuota q) {
        long tenantId = q.getTenantId();
        int maxCpu = nz(q.getMaxCpu());
        int maxMem = nz(q.getMaxMemoryMb());
        int maxGpu = nz(q.getMaxGpu());
        int maxRt = nz(q.getMaxRunningTasks());
        int usedCpu = nz(q.getUsedCpu());
        int usedMem = nz(q.getUsedMemoryMb());
        int usedGpu = nz(q.getUsedGpu());
        int run = nz(q.getRunningTasks());
        return new ResourceQuotaDetailResponse(
                tenantId,
                maxCpu,
                maxMem,
                maxGpu,
                maxRt,
                nz(q.getMaxPendingTasks()),
                usedCpu,
                usedMem,
                usedGpu,
                run,
                util(usedCpu, maxCpu),
                util(usedMem, maxMem),
                util(usedGpu, maxGpu),
                util(run, maxRt));
    }

    private static double util(int used, int max) {
        if (max <= 0) {
            return 0;
        }
        return Math.min(1.0, (double) used / max);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
