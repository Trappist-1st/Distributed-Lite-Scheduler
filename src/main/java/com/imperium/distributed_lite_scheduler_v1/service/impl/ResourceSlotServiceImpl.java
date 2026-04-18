package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.ResourceUsageStatuses;
import com.imperium.distributed_lite_scheduler_v1.mapper.ProjectMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceSlotMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceUsageMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceUsageRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReleaseResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceUsage;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ResourceSlotServiceImpl implements ResourceSlotService {

    private static final Logger log = LoggerFactory.getLogger(ResourceSlotServiceImpl.class);

    private static final String NODE_STATUS_ONLINE = "ONLINE";

    private static final String SLOT_CPU = "CPU";
    private static final String SLOT_GPU = "GPU";
    private static final String SLOT_MEMORY = "MEMORY";

    private static final String USAGE_RESERVED = ResourceUsageStatuses.RESERVED;
    private static final String USAGE_RUNNING = ResourceUsageStatuses.RUNNING;
    private static final String USAGE_RELEASED = ResourceUsageStatuses.RELEASED;

    private static final Set<String> RESERVE_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");

    private final ResourceSlotMapper resourceSlotMapper;
    private final ResourceUsageMapper resourceUsageMapper;
    private final ResourceNodeMapper resourceNodeMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final TenantAccessGuard tenantAccessGuard;
    private final ResourceQuotaService resourceQuotaService;

    public ResourceSlotServiceImpl(
            ResourceSlotMapper resourceSlotMapper,
            ResourceUsageMapper resourceUsageMapper,
            ResourceNodeMapper resourceNodeMapper,
            TaskInstanceMapper taskInstanceMapper,
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            TenantAccessGuard tenantAccessGuard,
            ResourceQuotaService resourceQuotaService) {
        this.resourceSlotMapper = resourceSlotMapper;
        this.resourceUsageMapper = resourceUsageMapper;
        this.resourceNodeMapper = resourceNodeMapper;
        this.taskInstanceMapper = taskInstanceMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.tenantAccessGuard = tenantAccessGuard;
        this.resourceQuotaService = resourceQuotaService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ReserveResourceResponse> reserve(ReserveResourceRequest request) {
        if (request == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "请求不能为空");
        }

        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                RESERVE_ROLES, "当前角色无资源预留权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "缺少租户上下文");
        }
        long principalTenantId = access.getData().principal().tenantId();
        if (principalTenantId != request.tenantId()) {
            return Result.failure(ResultCode.FORBIDDEN, "租户上下文与请求不一致");
        }

        int cpu = request.cpu();
        int mem = request.memoryMb();
        int gpu = request.gpu();
        if (cpu <= 0 && mem <= 0 && gpu <= 0) {
            return Result.failure(ResultCode.BAD_REQUEST, "资源需求至少一项大于 0");
        }

        if (request.taskInstanceId() == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "任务实例ID不能为空");
        }

        // 非 RELEASED 的流水均视为仍占用租户/实例语义，必须先 release 才能再次 reserve（含 FAILED 等终态未回补场景）
        Long blockingCnt = resourceUsageMapper.selectCount(
                new LambdaQueryWrapper<ResourceUsage>()
                        .eq(ResourceUsage::getTaskInstanceId, request.taskInstanceId())
                        .in(ResourceUsage::getStatus, ResourceUsageStatuses.BLOCK_NEW_RESERVE_UNTIL_RELEASED));
        if (blockingCnt != null && blockingCnt > 0) {
            return Result.failure(ResultCode.CONFLICT, "该任务实例仍有未释放的资源流水，请先 release");
        }

        Result<Long> tenantCheck = resolveTenantForTaskInstance(request.taskInstanceId());
        if (!tenantCheck.isSuccess()) {
            return Result.failure(tenantCheck.getCode(), tenantCheck.getMessage());
        }
        if (!tenantCheck.getData().equals(request.tenantId())) {
            return Result.failure(ResultCode.FORBIDDEN, "任务实例不属于当前租户");
        }

        CandidateNodes candidateNodes = resolveCandidateNodes(request.candidateNodeIds());
        List<Long> candidates = candidateNodes.ids();
        if (candidates.isEmpty()) {
            return Result.failure(ResultCode.BAD_REQUEST, "无 ONLINE 候选节点");
        }
        Map<Long, ResourceNode> onlineById = candidateNodes.onlineById();

        /*
         * P0 配额竞态修复：先 tryConsume（单条 SQL 条件更新），再抢槽位写流水。
         * 原因：@Transactional 方法内「return Result.failure」仍会提交事务；若在扣槽位之后才 consume 失败，
         * 仅靠抛异常回滚不够覆盖所有 return 分支。失败路径在返回前显式 releaseForReserve 与 consume 对称。
         * 已删除仅内存预检 evaluateReserveFeasibility：与原子 consume 重复且不能消除竞态。
         */
        if (!resourceQuotaService.tryConsumeForReserve(request.tenantId(), cpu, mem, gpu)) {
            return Result.failure(ResultCode.BAD_REQUEST, "租户配额不足（含并发竞争）");
        }

        for (Long nodeId : candidates) {
            if (nodeId == null) {
                continue;
            }
            ResourceNode node = onlineById.get(nodeId);
            if (node == null) {
                continue;
            }
            ensureSlotsInitialized(node);
            if (!tryDecrementAll(nodeId, cpu, mem, gpu)) {
                continue;
            }
            try {
                ResourceUsage usage = new ResourceUsage();
                usage.setTenantId(request.tenantId());
                usage.setTaskInstanceId(request.taskInstanceId());
                usage.setNodeId(nodeId);
                usage.setCpuUsed(cpu);
                usage.setMemoryMbUsed(mem);
                usage.setGpuUsed(gpu);
                usage.setStatus(USAGE_RESERVED);
                usage.setReason(null);
                usage.setCreatedAt(LocalDateTime.now());
                int ins = resourceUsageMapper.insert(usage);
                if (ins != 1) {
                    undoDecrementAll(nodeId, cpu, mem, gpu);
                    rollbackQuotaConsumed(request.tenantId(), cpu, mem, gpu);
                    return Result.failure(ResultCode.INTERNAL_ERROR, "写入资源使用流水失败");
                }
                int tu = taskInstanceMapper.update(
                        null,
                        new LambdaUpdateWrapper<TaskInstance>()
                                .eq(TaskInstance::getId, request.taskInstanceId())
                                .set(TaskInstance::getResourceNodeId, nodeId));
                if (tu != 1) {
                    // 仅抛异常：由事务回滚撤销本方法内已执行的 quota consume、槽位扣减与 usage 插入，避免先 undo 再回滚造成双重回补
                    throw new IllegalStateException("更新任务实例节点绑定失败，taskInstanceId=" + request.taskInstanceId());
                }
                log.info(
                        "resource reserve ok tenantId={} taskInstanceId={} nodeId={} usageId={} cpu={} memMb={} gpu={}",
                        request.tenantId(), request.taskInstanceId(), nodeId, usage.getId(), cpu, mem, gpu);
                return Result.success(new ReserveResourceResponse(nodeId, usage.getId(), cpu, mem, gpu));
            } catch (DataIntegrityViolationException e) {
                undoDecrementAll(nodeId, cpu, mem, gpu);
                rollbackQuotaConsumed(request.tenantId(), cpu, mem, gpu);
                log.warn(
                        "resource reserve conflict tenantId={} taskInstanceId={} nodeId={} detail={}",
                        request.tenantId(), request.taskInstanceId(), nodeId, e.getMessage());
                return Result.failure(ResultCode.CONFLICT, "资源预留冲突，请重试");
            }
        }

        rollbackQuotaConsumed(request.tenantId(), cpu, mem, gpu);
        return Result.failure(ResultCode.BAD_REQUEST, "无可用节点满足资源需求");
    }

    /**
     * 与 tryConsumeForReserve 对称回补配额；用于「已 consume 但槽位/流水路径失败」且方法即将 return failure 的场景。
     */
    private void rollbackQuotaConsumed(long tenantId, int cpu, int mem, int gpu) {
        resourceQuotaService.releaseForReserve(tenantId, cpu, mem, gpu);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> release(ReleaseResourceRequest request) {
        if (request == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "请求不能为空");
        }

        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                RESERVE_ROLES, "当前角色无资源释放权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "缺少租户上下文");
        }
        long principalTenantId = access.getData().principal().tenantId();

        if (request.usageId() == null && request.taskInstanceId() == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "usageId 与 taskInstanceId 至少填一个");
        }

        ResourceUsage active = findActiveUsage(request);
        if (active == null) {
            if (isAlreadyReleased(request)) {
                return Result.success();
            }
            return Result.failure(ResultCode.NOT_FOUND, "未找到可释放的资源占用");
        }
        if (active.getTenantId() == null || principalTenantId != active.getTenantId()) {
            return Result.failure(ResultCode.FORBIDDEN, "无权释放该资源占用");
        }
        if (active.getNodeId() == null) {
            log.warn("release skipped slot restore: usageId={} has null nodeId", active.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        String reasonText = buildReleaseReason(request.reason(), request.remark());
        int u = resourceUsageMapper.update(
                null,
                new LambdaUpdateWrapper<ResourceUsage>()
                        .eq(ResourceUsage::getId, active.getId())
                        .in(ResourceUsage::getStatus, ResourceUsageStatuses.RELEASABLE_STATUSES)
                        .set(ResourceUsage::getStatus, USAGE_RELEASED)
                        .set(ResourceUsage::getReason, reasonText)
                        .set(ResourceUsage::getReleasedAt, now));
        if (u != 1) {
            return Result.failure(ResultCode.CONFLICT, "资源占用状态已变更，请重试");
        }

        if (active.getNodeId() != null) {
            restoreSlots(active.getNodeId(), active.getCpuUsed(), active.getMemoryMbUsed(), active.getGpuUsed());
        }
        resourceQuotaService.releaseForReserve(
                active.getTenantId(),
                nz(active.getCpuUsed()),
                nz(active.getMemoryMbUsed()),
                nz(active.getGpuUsed()));
        log.info(
                "resource release ok usageId={} taskInstanceId={} tenantId={} nodeId={} reason={}",
                active.getId(), active.getTaskInstanceId(), active.getTenantId(), active.getNodeId(), reasonText);
        return Result.success();
    }

    @Override
    public Result<List<ResourceUsage>> listUsage(ListResourceUsageRequest request) {
        if (request == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "请求不能为空");
        }

        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                RESERVE_ROLES, "当前角色无查看资源流水权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        if (access.getData().principal().tenantId() == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "缺少租户上下文");
        }
        long principalTenantId = access.getData().principal().tenantId();
        if (principalTenantId != request.tenantId()) {
            return Result.failure(ResultCode.FORBIDDEN, "租户上下文与请求不一致");
        }

        int offset = (request.page() - 1) * request.size();
        LambdaQueryWrapper<ResourceUsage> qw = new LambdaQueryWrapper<ResourceUsage>()
                .eq(ResourceUsage::getTenantId, request.tenantId());
        if (StringUtils.hasText(request.status())) {
            qw.eq(ResourceUsage::getStatus, request.status().trim().toUpperCase(Locale.ROOT));
        }
        if (request.nodeId() != null) {
            qw.eq(ResourceUsage::getNodeId, request.nodeId());
        }
        qw.orderByDesc(ResourceUsage::getCreatedAt).orderByDesc(ResourceUsage::getId)
                .last("LIMIT " + offset + ", " + request.size());
        return Result.success(resourceUsageMapper.selectList(qw));
    }

    /**
     * P1：一次 IN 查询拉齐候选 ONLINE 节点，避免对每个 nodeId 单独 selectById。
     */
    /**
     * 解析候选节点：显式 id 列表走一次 IN 查询；未指定时沿用「全量 ONLINE」单次查询结果，避免二次打库。
     */
    private CandidateNodes resolveCandidateNodes(List<Long> candidateNodeIds) {
        if (candidateNodeIds != null && !candidateNodeIds.isEmpty()) {
            List<Long> ordered = new ArrayList<>();
            for (Long id : candidateNodeIds) {
                if (id != null && !ordered.contains(id)) {
                    ordered.add(id);
                }
            }
            return new CandidateNodes(ordered, loadOnlineCandidatesById(ordered));
        }
        List<ResourceNode> online = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, NODE_STATUS_ONLINE)
                        .orderByAsc(ResourceNode::getId));
        Map<Long, ResourceNode> map = online.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(ResourceNode::getId, n -> n, (a, b) -> a));
        List<Long> ids = online.stream().map(ResourceNode::getId).toList();
        return new CandidateNodes(ids, map);
    }

    private Map<Long, ResourceNode> loadOnlineCandidatesById(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = candidateIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ResourceNode> rows = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .in(ResourceNode::getId, ids)
                        .eq(ResourceNode::getStatus, NODE_STATUS_ONLINE));
        return rows.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(ResourceNode::getId, n -> n, (a, b) -> a));
    }

    private record CandidateNodes(List<Long> ids, Map<Long, ResourceNode> onlineById) {
    }

    /**
     * 按 CPU → MEMORY → GPU 顺序尝试原子扣减；任一失败则回补已成功的维度，避免“只扣到一半”。
     */
    private boolean tryDecrementAll(Long nodeId, int cpu, int mem, int gpu) {
        int r1 = decLine(nodeId, SLOT_CPU, cpu);
        if (r1 != 1) {
            return false;
        }
        int r2 = decLine(nodeId, SLOT_MEMORY, mem);
        if (r2 != 1) {
            undoDecrementLine(nodeId, SLOT_CPU, cpu);
            return false;
        }
        int r3 = decLine(nodeId, SLOT_GPU, gpu);
        if (r3 != 1) {
            undoDecrementLine(nodeId, SLOT_CPU, cpu);
            undoDecrementLine(nodeId, SLOT_MEMORY, mem);
            return false;
        }
        return true;
    }

    private void undoDecrementAll(Long nodeId, int cpu, int mem, int gpu) {
        undoDecrementLine(nodeId, SLOT_CPU, cpu);
        undoDecrementLine(nodeId, SLOT_MEMORY, mem);
        undoDecrementLine(nodeId, SLOT_GPU, gpu);
    }

    private int decLine(Long nodeId, String type, int amount) {
        if (amount <= 0) {
            return 1;
        }
        return resourceSlotMapper.decreaseIfEnough(nodeId, type, amount);
    }

    private void undoDecrementLine(Long nodeId, String type, int amount) {
        if (amount <= 0) {
            return;
        }
        int n = resourceSlotMapper.increaseAvailable(nodeId, type, amount);
        if (n != 1) {
            log.error("槽位回补失败 nodeId={} type={} amount={}", nodeId, type, amount);
            throw new IllegalStateException("槽位回补失败 nodeId=" + nodeId + " type=" + type);
        }
    }

    private void restoreSlots(Long nodeId, Integer cpu, Integer mem, Integer gpu) {
        undoDecrementLine(nodeId, SLOT_CPU, nz(cpu));
        undoDecrementLine(nodeId, SLOT_MEMORY, nz(mem));
        undoDecrementLine(nodeId, SLOT_GPU, nz(gpu));
    }

    /**
     * P2：单条 INSERT IGNORE 初始化三行槽位，降低并发下逐行判断的窗口。
     */
    private void ensureSlotsInitialized(ResourceNode node) {
        if (node == null || node.getId() == null) {
            return;
        }
        long nid = node.getId();
        int tc = nz(node.getTotalCpu());
        int ac = cappedAvail(tc, nz(node.getAvailableCpu()));
        int tm = nz(node.getTotalMemoryMb());
        int am = cappedAvail(tm, nz(node.getAvailableMemoryMb()));
        int tg = nz(node.getTotalGpu());
        int ag = cappedAvail(tg, nz(node.getAvailableGpu()));
        resourceSlotMapper.insertSlotsIfAbsent(nid, tc, ac, tm, am, tg, ag);
    }

    private static int cappedAvail(int total, int avail) {
        return Math.min(total, Math.max(0, avail));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private Result<Long> resolveTenantForTaskInstance(Long taskInstanceId) {
        TaskInstance ti = taskInstanceMapper.selectById(taskInstanceId);
        if (ti == null) {
            return Result.failure(ResultCode.NOT_FOUND, "任务实例不存在");
        }
        Task task = taskMapper.selectById(ti.getTaskId());
        if (task == null) {
            return Result.failure(ResultCode.NOT_FOUND, "任务定义不存在");
        }
        Project project = projectMapper.selectById(task.getProjectId());
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, "项目不存在");
        }
        return Result.success(project.getTenantId());
    }

    private ResourceUsage findActiveUsage(ReleaseResourceRequest request) {
        if (request.usageId() != null) {
            return resourceUsageMapper.selectOne(
                    new LambdaQueryWrapper<ResourceUsage>()
                            .eq(ResourceUsage::getId, request.usageId())
                            .in(ResourceUsage::getStatus, ResourceUsageStatuses.RELEASABLE_STATUSES));
        }
        return resourceUsageMapper.selectOne(
                new LambdaQueryWrapper<ResourceUsage>()
                        .eq(ResourceUsage::getTaskInstanceId, request.taskInstanceId())
                        .in(ResourceUsage::getStatus, ResourceUsageStatuses.RELEASABLE_STATUSES)
                        .orderByDesc(ResourceUsage::getId)
                        .last("LIMIT 1"));
    }

    private boolean isAlreadyReleased(ReleaseResourceRequest request) {
        if (request.usageId() != null) {
            ResourceUsage u = resourceUsageMapper.selectById(request.usageId());
            return u != null && USAGE_RELEASED.equalsIgnoreCase(safe(u.getStatus()));
        }
        Long cnt = resourceUsageMapper.selectCount(
                new LambdaQueryWrapper<ResourceUsage>()
                        .eq(ResourceUsage::getTaskInstanceId, request.taskInstanceId())
                        .eq(ResourceUsage::getStatus, USAGE_RELEASED));
        return cnt != null && cnt > 0;
    }

    private static String buildReleaseReason(String reason, String remark) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        if (!StringUtils.hasText(remark)) {
            return reason.trim();
        }
        return reason.trim() + " | " + remark.trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
