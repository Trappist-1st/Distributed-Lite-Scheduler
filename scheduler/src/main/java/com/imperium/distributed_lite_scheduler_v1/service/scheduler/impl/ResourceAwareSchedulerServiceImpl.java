package com.imperium.distributed_lite_scheduler_v1.service.scheduler.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.NodeType;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReleaseResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceRequirement;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskWithPriority;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskDispatchService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.ResourceAwareSchedulerService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.TaskScheduleLock;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 资源感知调度器（P3-4）：在优先级排序基础上按 Best Fit 选择节点并调度。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "scheduler.strategy", havingValue = "resource-aware", matchIfMissing = true)
public class ResourceAwareSchedulerServiceImpl implements ResourceAwareSchedulerService {

    private static final int BATCH_SIZE = 100;
    private static final double FIT_SCORE_REJECT = -1.0;
    private static final double PRIORITY_WEIGHT = 10.0;
    private static final double AGING_WEIGHT = 0.1;

    private final TaskInstanceMapper taskInstanceMapper;
    private final ResourceNodeMapper resourceNodeMapper;
    private final ResourceSlotService resourceSlotService;
    private final ResourceQuotaService resourceQuotaService;
    private final TaskScheduleLock taskScheduleLock;
    private final TaskDispatchService taskDispatchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResourceAwareSchedulerServiceImpl(
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

    /**
     * 调度主循环（Leader 选举由 {@link com.imperium.distributed_lite_scheduler_v1.service.scheduler.SchedulerLoopRunner} 负责）：
     * 1) 扫描待调度任务
     * 2) 查询在线节点并执行 Best Fit 选点
     * 3) 提交单任务调度
     */
    @Override
    public void scheduleLoop() {
        long loopStart = System.currentTimeMillis();
        try {
            List<TaskWithPriority> tasks = scanPendingTasksWithPriority(BATCH_SIZE);
            if (tasks.isEmpty()) {
                log.debug("无待调度任务");
                return;
            }
            int successCount = 0;
            int skipCount = 0;
            for (TaskWithPriority twp : tasks) {
                TaskInstance t = twp == null ? null : twp.getTask();
                if (t == null || t.getId() == null) {
                    continue;
                }
                if (scheduleTask(t)) {
                    successCount++;
                } else {
                    skipCount++;
                }
            }
            long elapsed = System.currentTimeMillis() - loopStart;
            log.info("资源感知调度周期完成 total={} success={} skip={} elapsedMs={}",
                    tasks.size(), successCount, skipCount, elapsed);
        } catch (Exception e) {
            log.error("资源感知调度周期异常", e);
        }
    }

    /**
     * 扫描待调度任务并附带优先级信息。
     */
    @Override
    public List<TaskWithPriority> scanPendingTasksWithPriority(int limit) {
        try {
            if (limit <= 0) {
                return Collections.emptyList();
            }
            limit = Math.min(limit, BATCH_SIZE);
            List<Long> ids = taskInstanceMapper.selectPendingTaskIdsByPriority(limit, PRIORITY_WEIGHT, AGING_WEIGHT);
            if (ids == null || ids.isEmpty()) {
                return Collections.emptyList();
            }
            return buildWithPriorityFromOrderedIds(ids);
        } catch (Exception e) {
            log.error("资源感知：查询待调度任务失败 limit={}", limit, e);
            return Collections.emptyList();
        }
    }

    /**
     * 调度单个任务（入口方法）。
     */
    @Override
    public boolean scheduleTask(TaskInstance taskInstance) {
        if (taskInstance == null || taskInstance.getId() == null) {
            log.warn("无效的任务实例，无法调度 taskInstance={}", taskInstance);
            return false;
        }
        RLock taskLock = taskScheduleLock.lockFor(taskInstance.getId());
        try {
            if (!taskScheduleLock.tryAcquire(taskLock)) {
                log.debug("任务调度锁获取失败，跳过 taskId={}", taskInstance.getId());
                return false;
            }
            try {
                TaskInstance latest = taskInstanceMapper.selectById(taskInstance.getId());
                if (latest == null) {
                    log.warn("任务不存在，跳过调度 taskId={}", taskInstance.getId());
                    return false;
                }
                if (!TaskInstanceStatus.PENDING.getCode().equals(latest.getStatus())) {
                    log.debug("任务状态非PENDING，跳过 taskId={} status={}", latest.getId(), latest.getStatus());
                    return false;
                }
                if (!checkQuota(latest)) {
                    log.debug("任务配额检查未通过，跳过 taskId={}", latest.getId());
                    return false;
                }
                List<ResourceNode> onlineNodes = listOnlineNodes();
                ResourceNode best = selectBestNode(latest, onlineNodes);
                if (best == null) {
                    log.debug("无可用BestFit节点，跳过 taskId={}", latest.getId());
                    return false;
                }
                return finalizeDispatch(latest, best);
            } finally {
                taskScheduleLock.release(taskLock);
            }
        } catch (Exception e) {
            log.error("资源感知任务调度异常 taskId={}", taskInstance.getId(), e);
            return false;
        }
    }

    /**
     * 调度任务到指定节点（内部流程）。
     * <p>
     * 与 {@link #scheduleTask(TaskInstance)} 类似，但跳过 Best Fit，直接使用调用方给出的节点（仍会二次拉库校验 ONLINE 与 canFit）。
     */
    private boolean scheduleTask(TaskInstance taskInstance, ResourceNode node) {
        if (taskInstance == null || taskInstance.getId() == null || node == null || node.getId() == null) {
            log.warn("调度到指定节点：参数无效");
            return false;
        }
        RLock taskLock = taskScheduleLock.lockFor(taskInstance.getId());
        try {
            if (!taskScheduleLock.tryAcquire(taskLock)) {
                log.debug("任务调度锁获取失败 taskId={}", taskInstance.getId());
                return false;
            }
            try {
                TaskInstance latest = taskInstanceMapper.selectById(taskInstance.getId());
                if (latest == null) {
                    log.warn("任务不存在 taskId={}", taskInstance.getId());
                    return false;
                }
                if (!TaskInstanceStatus.PENDING.getCode().equals(latest.getStatus())) {
                    log.debug("任务状态非PENDING taskId={} status={}", latest.getId(), latest.getStatus());
                    return false;
                }
                if (!checkQuota(latest)) {
                    return false;
                }
                ResourceNode pinned = resourceNodeMapper.selectById(node.getId());
                if (pinned == null || !"ONLINE".equals(pinned.getStatus())) {
                    log.debug("指定节点不可用或未在线 nodeId={}", node.getId());
                    return false;
                }
                ResourceRequirement req = parseRequirement(latest.getResourceRequirement());
                if (!canFit(pinned, req)) {
                    log.debug("指定节点资源不满足任务 taskId={} nodeId={}", latest.getId(), pinned.getId());
                    return false;
                }
                return finalizeDispatch(latest, pinned);
            } finally {
                taskScheduleLock.release(taskLock);
            }
        } catch (Exception e) {
            log.error("资源感知：调度到指定节点异常 taskId={} nodeId={}", taskInstance.getId(), node.getId(), e);
            return false;
        }
    }

    /**
     * 在已通过任务锁与 PENDING/配额校验后，执行预留、乐观锁状态流转与执行器提交。
     */
    private boolean finalizeDispatch(TaskInstance latest, ResourceNode node) {
        Long reservedUsageId = null;
        try {
            reservedUsageId = reserveResource(latest, node);
            if (reservedUsageId == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            int updated = taskInstanceMapper.updateStatusWithVersion(
                    latest.getId(),
                    TaskInstanceStatus.PENDING.getCode(),
                    TaskInstanceStatus.RUNNING.getCode(),
                    latest.getVersion() == null ? 0 : latest.getVersion(),
                    node.getId(),
                    now,
                    now
            );
            if (updated != 1) {
                rollbackReservation(latest, reservedUsageId);
                log.warn("状态流转失败（可能并发冲突） taskId={} version={}", latest.getId(), latest.getVersion());
                return false;
            }
            if (!submitToExecutor(latest, node)) {
                rollbackAfterDispatchFailure(latest, reservedUsageId);
                log.warn("提交执行器失败，已回滚 taskId={} nodeId={}", latest.getId(), node.getId());
                return false;
            }
            log.info("资源感知任务调度成功 taskId={} nodeId={} basePriority={}", latest.getId(), node.getId(), latest.getPriority());
            return true;
        } catch (Exception e) {
            if (reservedUsageId != null) {
                rollbackReservation(latest, reservedUsageId);
            }
            log.error("finalizeDispatch 异常 taskId={}", latest.getId(), e);
            return false;
        }
    }

    private List<TaskWithPriority> buildWithPriorityFromOrderedIds(List<Long> ids) {
        List<TaskInstance> rows = taskInstanceMapper.selectByIds(ids);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, TaskInstance> byId = new HashMap<>(rows.size() * 2);
        for (TaskInstance row : rows) {
            if (row != null && row.getId() != null) {
                byId.put(row.getId(), row);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<TaskWithPriority> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            TaskInstance ti = byId.get(id);
            if (ti == null || !TaskInstanceStatus.PENDING.getCode().equals(ti.getStatus())) {
                continue;
            }
            TaskWithPriority twp = new TaskWithPriority();
            twp.setTask(ti);
            twp.setPriorityBonus(0);
            long waitMinutes = ti.getSubmitTime() == null ? 0L : ChronoUnit.MINUTES.between(ti.getSubmitTime(), now);
            twp.setWaitingMinutes(waitMinutes);
            twp.setEffectivePriority(computeEffectivePriority(ti, now));
            out.add(twp);
        }
        return out;
    }

    private static double computeEffectivePriority(TaskInstance ti, LocalDateTime now) {
        long waitSec = ti.getSubmitTime() == null ? 0L : ChronoUnit.SECONDS.between(ti.getSubmitTime(), now);
        double base = ti.getPriority() == null ? 0.0 : ti.getPriority() / 10.0;
        return PRIORITY_WEIGHT * base + AGING_WEIGHT * (waitSec / 3600.0);
    }

    private boolean checkQuota(TaskInstance task) {
        Long tenantId = task.getTenantId();
        if (tenantId == null) {
            log.warn("任务缺少tenantId，跳过调度 taskId={}", task.getId());
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

    private List<ResourceNode> listOnlineNodes() {
        List<ResourceNode> onlineNodes = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, "ONLINE")
                        .orderByAsc(ResourceNode::getId)
        );
        return onlineNodes == null ? Collections.emptyList() : onlineNodes;
    }

    private Long reserveResource(TaskInstance task, ResourceNode node) {
        Long tenantId = task.getTenantId();
        if (tenantId == null) {
            return null;
        }
        ResourceRequirement requirement = parseRequirement(task.getResourceRequirement());
        ReserveResourceRequest request = new ReserveResourceRequest(
                tenantId,
                task.getId(),
                toNonNegativeInt(requirement.getCpu()),
                toNonNegativeInt(requirement.getMemoryMb()),
                toNonNegativeInt(requirement.getGpu()),
                List.of(node.getId())
        );
        Result<ReserveResourceResponse> result = resourceSlotService.reserve(request);
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("资源预留失败 taskId={} nodeId={} reason={}", task.getId(), node.getId(), result.getMessage());
            return null;
        }
        return result.getData().usageId();
    }

    private void rollbackReservation(TaskInstance task, Long reservedUsageId) {
        if (reservedUsageId == null || reservedUsageId <= 0) {
            return;
        }
        Result<Void> releaseResult = resourceSlotService.release(
                new ReleaseResourceRequest(task.getId(), reservedUsageId, "FAILED", "resource-aware scheduler rollback")
        );
        if (!releaseResult.isSuccess()) {
            log.error("资源回滚失败 taskId={} usageId={} reason={}", task.getId(), reservedUsageId, releaseResult.getMessage());
        }
    }

    private boolean submitToExecutor(TaskInstance task, ResourceNode node) {
        return taskDispatchService.dispatch(task, node);
    }

    private void rollbackAfterDispatchFailure(TaskInstance task, Long reservedUsageId) {
        task.setStatus(TaskInstanceStatus.PENDING.getCode());
        task.setResourceNodeId(null);
        task.setStartTime(null);
        task.setScheduledTime(null);
        taskInstanceMapper.updateById(task);
        rollbackReservation(task, reservedUsageId);
    }

    private static int toNonNegativeInt(Number value) {
        if (value == null) {
            return 0;
        }
        return Math.max(value.intValue(), 0);
    }

    // -------------------------------------------------------------------------
    // P3-4 资源感知核心（以下为建议实现顺序：解析 → 可容纳 → 类型匹配 → 综合分 → 选点）
    // -------------------------------------------------------------------------

    /**
     * 解析任务实例上的 resourceRequirement JSON。
     * <p>
     * 说明：与 FIFO/优先级调度器中私有解析类似，但需覆盖 ResourceRequirement 扩展字段
     * （如 {@link com.imperium.distributed_lite_scheduler_v1.constant.NodeType}、excludeNodeIds、
     * gpuModel、minCpuCores、minMemoryMb、preferredNodeId 等），供 canFit / calculateTypeMatch 使用。
     */
    private ResourceRequirement parseRequirement(String requirementJson) {
        if (requirementJson == null || requirementJson.isBlank()) {
            return new ResourceRequirement();
        }
        try {
            JsonNode root = objectMapper.readTree(requirementJson);
            ResourceRequirement requirement = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
                    .treeToValue(root, ResourceRequirement.class);
            if (requirement == null) {
                log.warn("Parsed resource requirement is null, defaulting to zero requirement. json={}", requirementJson);
                return new ResourceRequirement();
            }

            JsonNode nodeTypeNode = root.get("nodeType");
            if (requirement.getNodeType() == null && nodeTypeNode != null && nodeTypeNode.isTextual()) {
                String raw = nodeTypeNode.asText().trim();
                if (!raw.isEmpty()) {
                    try {
                        requirement.setNodeType(NodeType.valueOf(raw.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        log.warn("Unknown nodeType '{}' in resource requirement JSON, ignoring. json={}", raw, requirementJson);
                    }
                }
            }

            double cpu = requirement.getCpu() != null ? Math.max(0.0, requirement.getCpu()) : 0.0;
            requirement.setCpu(cpu);
            long memoryMb = requirement.getMemoryMb() != null ? Math.max(0L, requirement.getMemoryMb()) : 0L;
            requirement.setMemoryMb(memoryMb);
            int gpu = requirement.getGpu() != null ? Math.max(0, requirement.getGpu()) : 0;
            requirement.setGpu(gpu);

            Integer minCpu = requirement.getMinCpuCores();
            if (minCpu != null) {
                requirement.setMinCpuCores(Math.max(0, minCpu));
            }
            Long minMem = requirement.getMinMemoryMb();
            if (minMem != null) {
                requirement.setMinMemoryMb(Math.max(0L, minMem));
            }

            String gpuModel = requirement.getGpuModel();
            if (gpuModel != null) {
                String trimmed = gpuModel.trim();
                requirement.setGpuModel(trimmed.isEmpty() ? null : trimmed);
            }

            if (requirement.getTags() == null) {
                requirement.setTags(Collections.emptyList());
            }
            if (requirement.getExcludeNodeIds() == null) {
                requirement.setExcludeNodeIds(Collections.emptyList());
            }

            return requirement;
        } catch (Exception e) {
            log.warn("资源需求JSON解析失败，按零需求处理 json={}", requirementJson, e);
            return new ResourceRequirement();
        }
    }

    /**
     * 判断单个节点是否<strong>硬性</strong>满足任务资源需求（不满足则根本不应进入打分）。
     */
    private boolean canFit(ResourceNode node, ResourceRequirement requirement) {
        // 业务逻辑（注释备忘）：
        // 1) node 为 null：返回 false。
        // 2) 节点状态：仅允许 ONLINE（或你项目约定的可用状态）；MAINTENANCE/OFFLINE 直接 false。
        // 3) CPU：availableCpu >= 需求 CPU（需求取自 requirement，注意 Double/Long 与 int 转换及 Math.max(0, ...)）。
        // 4) 内存：availableMemoryMb >= 需求 memoryMb。
        // 5) GPU：若需求 gpu > 0，则 availableGpu >= 需求；若需求 gpu == 0，可按策略仍要求节点有 GPU 或不要求（通常不要求）。
        // 6) 节点类型（可选扩展）：若 requirement 指定了 nodeType，则 node.getNodeType() 字符串需与之兼容（完全相等或允许 MIXED 等规则）。
        // 7) gpuModel（可选）：若 requirement 填了 gpuModel，节点 gpuModel 需匹配（忽略大小写或精确匹配由你约定）。
        // 8) minCpuCores / minMemoryMb（可选）：节点 total 或 available 是否不低于「最小节点规格」，与设计稿一致即可。
        // 9) excludeNodeIds：若当前 node.getId() 在排除列表中，返回 false。
        // 10) tags / NodeAffinity（若后续任务参数里单独挂亲和对象）：requiredTags 必须全部命中节点 labels；excludedTags 命中则 false——可先 TODO，仅注释占位。
        if (node == null || requirement == null) {
            return false;
        }
        if (!"ONLINE".equals(node.getStatus())) {
            return false;
        }
        double cpuNeed = requirement.getCpu() != null ? requirement.getCpu() : 0.0;
        int needCpuCores = (int) Math.ceil(Math.max(0.0, cpuNeed));
        long needMemoryMb = requirement.getMemoryMb() != null ? Math.max(0L, requirement.getMemoryMb()) : 0L;
        int needGpu = requirement.getGpu() != null ? Math.max(0, requirement.getGpu()) : 0;

        Integer availableCpu = node.getAvailableCpu();
        Integer availableMemoryMb = node.getAvailableMemoryMb();
        Integer availableGpu = node.getAvailableGpu();
        if (availableCpu == null || availableMemoryMb == null || availableGpu == null) {
            return false;
        }
        if (availableCpu < needCpuCores || availableMemoryMb < needMemoryMb) {
            return false;
        }
        if (needGpu > 0 && availableGpu < needGpu) {
            return false;
        }
        if (!hardConstraintNodeType(node, requirement)) {
            return false;
        }
        String reqGpuModel = requirement.getGpuModel();
        if (reqGpuModel != null && !reqGpuModel.isEmpty()) {
            String nodeGpuModel = node.getGpuModel();
            if (nodeGpuModel == null || !reqGpuModel.equalsIgnoreCase(nodeGpuModel.trim())) {
                return false;
            }
        }
        Integer minCpuCores = requirement.getMinCpuCores();
        if (minCpuCores != null && minCpuCores > 0) {
            Integer totalCpu = node.getTotalCpu();
            if (totalCpu == null || totalCpu < minCpuCores) {
                return false;
            }
        }
        Long minNodeMemoryMb = requirement.getMinMemoryMb();
        if (minNodeMemoryMb != null && minNodeMemoryMb > 0) {
            Integer totalMemoryMb = node.getTotalMemoryMb();
            if (totalMemoryMb == null || totalMemoryMb.longValue() < minNodeMemoryMb) {
                return false;
            }
        }
        Long nodeId = node.getId();
        if (nodeId != null && requirement.getExcludeNodeIds() != null) {
            for (Long excludedId : requirement.getExcludeNodeIds()) {
                if (excludedId != null && excludedId.equals(nodeId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 计算节点类型与任务需求的匹配度，供综合打分中的「异构匹配」项使用。
     * <p>
     * 返回值语义建议：越高越匹配（例如 0.0 ~ 1.0），具体区间与 calculateFitScore 内权重保持一致。
     */
    private double calculateTypeMatch(ResourceNode node, ResourceRequirement requirement) {
        // 业务逻辑（注释备忘，对齐设计稿 3.3 / 4.1）：
        // 1) 从 node.getNodeType() 解析为 NodeType 枚举（CPU/GPU/MIXED）；解析失败时的默认分（如 0.5 或 0）自行约定。
        // 2) 若任务需求 gpu > 0（GPU 任务）：
        //    - 节点 GPU → 1.0；MIXED → 0.5；CPU → 0.0（或更小）。
        // 3) 若任务需求 gpu == 0（CPU 为主任务）：
        //    - 节点 CPU → 1.0；MIXED → 0.8；GPU → 0.3（可用 GPU 跑 CPU 但不推荐）。
        // 4) 若 requirement 显式指定了 nodeType：可与上述规则组合（例如硬约束已在 canFit 处理，这里只做软分微调）。
        if (node == null || requirement == null) {
            return 0.0;
        }
        NodeType nodeKind = parseNodeTypeFromString(node.getNodeType());
        double base;
        if (nodeKind == null) {
            base = 0.5;
        } else {
            int needGpu = requirement.getGpu() != null ? Math.max(0, requirement.getGpu()) : 0;
            if (needGpu > 0) {
                base = switch (nodeKind) {
                    case GPU -> 1.0;
                    case MIXED -> 0.5;
                    case CPU -> 0.0;
                };
            } else {
                base = switch (nodeKind) {
                    case CPU -> 1.0;
                    case MIXED -> 0.8;
                    case GPU -> 0.3;
                };
            }
        }
        NodeType explicit = requirement.getNodeType();
        if (explicit != null && nodeKind == explicit) {
            base = Math.min(1.0, base + 0.1);
        }
        return base;
    }

    private static NodeType parseNodeTypeFromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NodeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean hardConstraintNodeType(ResourceNode node, ResourceRequirement requirement) {
        NodeType required = requirement.getNodeType();
        if (required == null) {
            return true;
        }
        NodeType actual = parseNodeTypeFromString(node.getNodeType());
        if (actual == null) {
            return false;
        }
        return switch (required) {
            case CPU -> actual == NodeType.CPU || actual == NodeType.MIXED;
            case GPU -> actual == NodeType.GPU || actual == NodeType.MIXED;
            case MIXED -> actual == NodeType.MIXED;
        };
    }

    /**
     * 计算节点与任务的<strong>综合匹配分</strong>：分数越高越优先被选为 Best Fit。
     * <p>
     * 约定：若节点不可能运行该任务（等同于 canFit 为 false），应返回负数（如 -1），方便调用方统一过滤。
     */
    private double calculateFitScore(ResourceNode node, TaskInstance task) {
        // 业务逻辑（注释备忘，对齐设计稿多维评分）：
        // 1) 调用 parseRequirement(task.getResourceRequirement()) 得到 req。
        // 2) 若 !canFit(node, req)：返回 -1（或统一常量 FIT_SCORE_REJECT）。
        // 3) 浪费率 waste（CPU/内存/GPU 维度）：
        //    - cpuWaste = (availableCpu - needCpu) / totalCpu（注意 total 为 0 时避免除零）。
        //    - 同理 memWaste、gpuWaste（totalGpu 为 0 且需求 gpu==0 时可令 gpuWaste=0）。
        //    - wasteScore = cpuWaste * wCpu + memWaste * wMem + gpuWaste * wGpu（权重如 0.4 / 0.3 / 0.3）。
        // 4) 负载因子 loadFactor：
        //    - 可用「1 - 当前负载率」；负载率可用 (totalCpu - availableCpu)/totalCpu，或用节点额外字段若后续有。
        // 5) typeMatch = calculateTypeMatch(node, req)。
        // 6) 综合：例如 (1 - wasteScore) * 0.5 + loadFactor * 0.3 + typeMatch * 0.2（权重与设计稿一致可调）。
        // 7) 可选加分：preferredNodeId 与 node.getId() 一致时增加小幅 bonus。
        // 8) 确保返回值落在合理区间或至少保证「候选节点之间可比」；打 debug 日志时可输出各子项便于调参。
        if (node == null || task == null) {
            return FIT_SCORE_REJECT;
        }
        ResourceRequirement req = parseRequirement(task.getResourceRequirement());
        if (!canFit(node, req)) {
            return FIT_SCORE_REJECT;
        }
        double cpuNeed = req.getCpu() != null ? req.getCpu() : 0.0;
        int needCpu = (int) Math.ceil(Math.max(0.0, cpuNeed));
        long needMemMb = req.getMemoryMb() != null ? Math.max(0L, req.getMemoryMb()) : 0L;
        int needGpu = req.getGpu() != null ? Math.max(0, req.getGpu()) : 0;

        int availCpu = node.getAvailableCpu();
        int availMemMb = node.getAvailableMemoryMb();
        int availGpu = node.getAvailableGpu();

        Integer totalCpu = node.getTotalCpu();
        Integer totalMemMb = node.getTotalMemoryMb();
        Integer totalGpu = node.getTotalGpu();

        double cpuWaste = 0.0;
        if (totalCpu != null && totalCpu > 0) {
            cpuWaste = (double) (availCpu - needCpu) / totalCpu;
            cpuWaste = Math.max(0.0, Math.min(1.0, cpuWaste));
        }
        double memWaste = 0.0;
        if (totalMemMb != null && totalMemMb > 0) {
            memWaste = (double) (availMemMb - needMemMb) / totalMemMb;
            memWaste = Math.max(0.0, Math.min(1.0, memWaste));
        }
        double gpuWaste = 0.0;
        if (needGpu > 0 && totalGpu != null && totalGpu > 0) {
            gpuWaste = (double) (availGpu - needGpu) / totalGpu;
            gpuWaste = Math.max(0.0, Math.min(1.0, gpuWaste));
        }

        final double wCpu = 0.4;
        final double wMem = 0.3;
        final double wGpu = 0.3;
        double wasteScore = cpuWaste * wCpu + memWaste * wMem + gpuWaste * wGpu;
        wasteScore = Math.max(0.0, Math.min(1.0, wasteScore));

        double loadFactor;
        if (totalCpu != null && totalCpu > 0) {
            loadFactor = (double) availCpu / totalCpu;
            loadFactor = Math.max(0.0, Math.min(1.0, loadFactor));
        } else {
            loadFactor = 1.0;
        }

        double typeMatch = calculateTypeMatch(node, req);
        double score = (1.0 - wasteScore) * 0.5 + loadFactor * 0.3 + typeMatch * 0.2;

        Long preferred = req.getPreferredNodeId();
        Long nodeId = node.getId();
        if (preferred != null && nodeId != null && preferred.equals(nodeId)) {
            score += 0.05;
        }

        log.debug(
                "fitScore taskId={} nodeId={} cpuWaste={} memWaste={} gpuWaste={} wasteScore={} loadFactor={} typeMatch={} finalScore={}",
                task.getId(), nodeId, cpuWaste, memWaste, gpuWaste, wasteScore, loadFactor, typeMatch, score);
        return score;
    }

    /**
     * Best Fit：在候选节点列表中选出<strong>综合匹配分最高</strong>的一个节点。
     */
    private ResourceNode selectBestNode(TaskInstance task, List<ResourceNode> nodes) {
        // 业务逻辑（注释备忘）：
        // 1) task 为 null 或 task.getId() 为 null：返回 null。
        // 2) nodes 为 null 或空：返回 null。
        // 3) 遍历 nodes，对每个 node 调用 calculateFitScore(node, task)。
        // 4) 跳过得分 < 0（或 FIT_SCORE_REJECT）的节点。
        // 5) 在剩余候选中取最高分节点；若并列：可用 nodeId 升序打破平局，或 secondary 比较浪费度更小者。
        // 6) 若无一候选：返回 null；可选 debug 日志打印 taskId 与 requirement 摘要。
        // 7) 性能注意：后续可加并行流或缓存在线节点列表（设计稿 7.x），当前先保证语义正确。
        if (task == null || task.getId() == null) {
            return null;
        }
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        ResourceNode best = null;
        double bestScore = FIT_SCORE_REJECT;
        for (ResourceNode node : nodes) {
            if (node == null) {
                continue;
            }
            double score = calculateFitScore(node, task);
            if (score < 0) {
                continue;
            }
            if (best == null || score > bestScore
                    || (Double.compare(score, bestScore) == 0 && lowerNodeIdWins(node, best))) {
                best = node;
                bestScore = score;
            }
        }
        if (best == null) {
            ResourceRequirement rq = parseRequirement(task.getResourceRequirement());
            log.debug("无可用BestFit节点 taskId={} cpu={} memMb={} gpu={}", task.getId(), rq.getCpu(), rq.getMemoryMb(), rq.getGpu());
        }
        return best;
    }

    private static boolean lowerNodeIdWins(ResourceNode candidate, ResourceNode incumbent) {
        Long cid = candidate.getId();
        Long iid = incumbent.getId();
        if (cid == null || iid == null) {
            return false;
        }
        return cid < iid;
    }
}
