package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceRequirement;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskDispatchService;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 调度器公共基类，提取三种调度策略实现中重复的资源预留/回滚/提交逻辑。
 *
 * <p>子类只需关注差异化逻辑：
 * <ul>
 *   <li>{@link impl.FifoSchedulerServiceImpl}：FIFO 扫描 + 首个 ONLINE 节点选取</li>
 *   <li>{@link impl.PrioritySchedulerServiceImpl}：优先级扫描 + 首个 ONLINE 节点选取</li>
 *   <li>{@link impl.ResourceAwareSchedulerServiceImpl}：优先级扫描 + Best Fit 选点（覆盖 parseRequirement）</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractSchedulerService implements SchedulerService {

    protected final TaskInstanceMapper taskInstanceMapper;
    protected final ResourceNodeMapper resourceNodeMapper;
    protected final ResourceSlotService resourceSlotService;
    protected final ResourceQuotaService resourceQuotaService;
    protected final TaskScheduleLock taskScheduleLock;
    protected final TaskDispatchService taskDispatchService;

    /** 各子类共享同一 ObjectMapper 实例，避免重复创建。 */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractSchedulerService(
            TaskInstanceMapper taskInstanceMapper,
            ResourceNodeMapper resourceNodeMapper,
            ResourceSlotService resourceSlotService,
            ResourceQuotaService resourceQuotaService,
            TaskScheduleLock taskScheduleLock,
            TaskDispatchService taskDispatchService) {
        this.taskInstanceMapper = taskInstanceMapper;
        this.resourceNodeMapper = resourceNodeMapper;
        this.resourceSlotService = resourceSlotService;
        this.resourceQuotaService = resourceQuotaService;
        this.taskScheduleLock = taskScheduleLock;
        this.taskDispatchService = taskDispatchService;
    }

    // -------------------------------------------------------------------------
    // 公共调度逻辑
    // -------------------------------------------------------------------------

    protected boolean checkQuota(TaskInstance task) {
        Long tenantId = task.getTenantId();
        if (tenantId == null) {
            log.warn("任务缺少 tenantId，跳过调度 taskId={}", task.getId());
            return false;
        }
        ResourceRequirement requirement = parseRequirement(task.getResourceRequirement());
        QuotaCheckResponse quota = resourceQuotaService.evaluateReserveFeasibility(
                tenantId,
                toNonNegativeInt(requirement.getCpu()),
                toNonNegativeInt(requirement.getMemoryMb()),
                toNonNegativeInt(requirement.getGpu())
        );
        if (!quota.allowed()) {
            log.info("配额不足，任务跳过 taskId={} tenantId={} reason={}",
                    task.getId(), tenantId, quota.rejectReason());
            return false;
        }
        return true;
    }

    protected Long reserveResource(TaskInstance task, ResourceNode node) {
        Long tenantId = task.getTenantId();
        if (tenantId == null) {
            return null;
        }
        ResourceRequirement requirement = parseRequirement(task.getResourceRequirement());
        int cpu = toNonNegativeInt(requirement.getCpu());
        int mem = toNonNegativeInt(requirement.getMemoryMb());
        int gpu = toNonNegativeInt(requirement.getGpu());
        if (cpu == 0 && mem == 0 && gpu == 0) {
            return 0L;
        }
        ReserveResourceRequest request = new ReserveResourceRequest(
                tenantId,
                task.getId(),
                cpu,
                mem,
                gpu,
                List.of(node.getId())
        );
        Result<ReserveResourceResponse> result = resourceSlotService.reserveForScheduler(request);
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("资源预留失败 taskId={} nodeId={} reason={}", task.getId(), node.getId(), result.getMessage());
            return null;
        }
        return result.getData().usageId();
    }

    protected void rollbackReservation(TaskInstance task, Long reservedUsageId) {
        if (reservedUsageId == null || reservedUsageId <= 0) {
            return;
        }
        resourceSlotService.releaseForTaskInstanceSystem(task.getId(), "scheduler rollback");
    }

    /**
     * 执行器提交失败时的回滚：将任务状态重置为 PENDING，释放已预留资源。
     *
     * <p>注意：此处直接 {@code updateById} 绕过状态机，因为状态机路径 RUNNING→PENDING 已被移除；
     * 此回滚发生在 PENDING→RUNNING 之后、执行器接受任务之前，属于调度内部事务性操作。
     */
    protected void rollbackAfterDispatchFailure(TaskInstance task, Long reservedUsageId) {
        task.setStatus(TaskInstanceStatus.PENDING.getCode());
        task.setResourceNodeId(null);
        task.setStartTime(null);
        task.setScheduledTime(null);
        taskInstanceMapper.updateById(task);
        rollbackReservation(task, reservedUsageId);
    }

    protected boolean submitToExecutor(TaskInstance task, ResourceNode node) {
        return taskDispatchService.dispatch(task, node);
    }

    /**
     * 解析任务资源需求 JSON（基础版本，FIFO / Priority 使用）。
     * ResourceAware 子类覆盖此方法以解析更多字段（nodeType、gpuModel 等）。
     */
    protected ResourceRequirement parseRequirement(String json) {
        if (json == null || json.isBlank()) {
            return new ResourceRequirement();
        }
        try {
            return objectMapper.readValue(json, ResourceRequirement.class);
        } catch (Exception e) {
            log.warn("资源需求 JSON 解析失败，按零需求处理 json={}", json, e);
            return new ResourceRequirement();
        }
    }

    protected static int toNonNegativeInt(Number value) {
        if (value == null) {
            return 0;
        }
        return Math.max(value.intValue(), 0);
    }
}
