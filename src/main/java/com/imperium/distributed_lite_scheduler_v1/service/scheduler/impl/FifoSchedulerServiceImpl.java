package com.imperium.distributed_lite_scheduler_v1.service.scheduler.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatuses;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.QuotaCheckResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReleaseResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceRequirement;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.FifoSchedulerService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * FIFO 调度器实现（骨架）。
 */
@Slf4j
@Service
public class FifoSchedulerServiceImpl implements FifoSchedulerService {

    private static final int BATCH_SIZE = 100;
    private static final long SCHEDULE_INTERVAL_MS = 5000L;
    private static final int TASK_LOCK_WAIT_SECONDS = 1;
    private static final int TASK_LOCK_LEASE_SECONDS = 10;

    private final TaskInstanceMapper taskInstanceMapper;
    private final ResourceNodeMapper resourceNodeMapper;
    private final ResourceSlotService resourceSlotService;
    private final ResourceQuotaService resourceQuotaService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    //这里使用volatile是因为isLeader可能会被多个线程访问和修改，volatile可以保证线程之间的可见性和有序性，
    //确保所有线程都能看到最新的isLeader值，避免出现多个线程同时认为自己是Leader的情况。
    private volatile boolean isLeader = false;

    public FifoSchedulerServiceImpl(TaskInstanceMapper taskInstanceMapper,
                                    ResourceNodeMapper resourceNodeMapper,
                                    ResourceSlotService resourceSlotService,
                                    ResourceQuotaService resourceQuotaService,
                                    RedissonClient redissonClient) {
        this.taskInstanceMapper = taskInstanceMapper;
        this.resourceNodeMapper = resourceNodeMapper;
        this.resourceSlotService = resourceSlotService;
        this.resourceQuotaService = resourceQuotaService;
        this.redissonClient = redissonClient;
    }

    //FIFO调度主循环
    @Override
    @Scheduled(fixedRate = SCHEDULE_INTERVAL_MS)
    public void scheduleLoop() {
        //Step 0: 记录本轮调度开始时间，用于计算周期耗时与延迟指标。
        long loopStart = System.currentTimeMillis();

        // Step 1: Leader 选举校验（Redis 分布式锁）。
        // 目标：同一时刻仅允许一个调度器实例执行 scheduleLoop。
        if (!tryAcquireLeadership()) {
            log.debug("非 Leader 节点，跳过本轮调度");
            return;
        }

        log.info("开始调度周期");

        try {
            // Step 2: 扫描待调度任务（FIFO 核心：按 submit_time ASC）。
            List<TaskInstance> pendingTasks = scanPendingTasks(BATCH_SIZE);
            if (pendingTasks.isEmpty()) {
                log.debug("无待调度任务");
                return;
            }

            // Step 3: 逐个任务执行调度，统计成功/跳过数量。
            int successCount = 0;
            int skipCount = 0;
            for (TaskInstance task : pendingTasks) {
                boolean scheduled = scheduleTask(task);
                if (scheduled) {
                    successCount++;
                } else {
                    skipCount++;
                }
            }

            // Step 4: 打点并输出本轮结果日志。
            long elapsed = System.currentTimeMillis() - loopStart;
            log.info("调度周期完成 total={} success={} skip={} elapsedMs={}",
                    pendingTasks.size(), successCount, skipCount, elapsed);
        } catch (Exception e) {
            log.error("调度周期异常", e);
        }
    }

    //尝试获取Leader锁,Leader选举（使用Redis分布式锁实现）
    private boolean tryAcquireLeadership() {
        //首先创建锁，使用固定的key,所有竞争该角色的节点都使用同一把锁，谁拿到锁谁就是Leader
        String lockKey = "scheduler:leader:lock";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            //尝试获取锁，设置过期时间，防止死锁
            boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);

            if(acquired && !isLeader){
                isLeader = true;
                log.info("成功获取Leader锁，成为Leader节点");
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean scheduleTask(TaskInstance task) {
        if (task == null || task.getId() == null) {
            return false;
        }

        //这个锁是为了保证同一任务在调度过程中不会被多个线程同时处理，避免状态更新和资源预留的并发冲突。
        String lockKey = "task:schedule:lock:" + task.getId();
        RLock lock = redissonClient.getLock(lockKey);
        Long reservedUsageId = null;
        long startMs = System.currentTimeMillis();

        try {
            boolean acquired = lock.tryLock(TASK_LOCK_WAIT_SECONDS, TASK_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("任务调度锁获取失败，跳过 taskId={}", task.getId());
                return false;
            }

            TaskInstance latest = taskInstanceMapper.selectById(task.getId());
            if (latest == null) {
                log.warn("任务不存在，跳过调度 taskId={}", task.getId());
                return false;
            }
            if (!TaskInstanceStatuses.PENDING.equals(latest.getStatus())) {
                log.debug("任务状态非PENDING，跳过 taskId={} status={}", latest.getId(), latest.getStatus());
                return false;
            }

            if (!checkQuota(latest)) {
                log.debug("任务配额检查未通过，跳过 taskId={}", latest.getId());
                return false;
            }

            ResourceNode selectedNode = selectNode(latest);
            if (selectedNode == null) {
                log.debug("无可用ONLINE节点，跳过 taskId={}", latest.getId());
                return false;
            }

            reservedUsageId = reserveResource(latest, selectedNode);
            if (reservedUsageId == null) {
                log.debug("资源预留失败，跳过 taskId={} nodeId={}", latest.getId(), selectedNode.getId());
                return false;
            }

            LocalDateTime now = LocalDateTime.now();
            int updated = taskInstanceMapper.updateStatusWithVersion(
                    latest.getId(),
                    TaskInstanceStatuses.PENDING,
                    TaskInstanceStatuses.RUNNING,
                    latest.getVersion() == null ? 0 : latest.getVersion(),
                    selectedNode.getId(),
                    now,
                    now
            );

            if (updated != 1) {
                rollbackReservation(latest, reservedUsageId);
                log.warn("状态流转失败（可能并发冲突） taskId={} version={}", latest.getId(), latest.getVersion());
                return false;
            }

            if (!submitToExecutor(latest, selectedNode)) {
                rollbackAfterDispatchFailure(latest, reservedUsageId);
                log.warn("提交执行器失败，已回滚 taskId={} nodeId={}", latest.getId(), selectedNode.getId());
                return false;
            }

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("任务调度成功 taskId={} nodeId={} elapsedMs={}", latest.getId(), selectedNode.getId(), elapsed);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("任务调度被中断 taskId={}", task.getId(), e);
            return false;
        } catch (Exception e) {
            if (reservedUsageId != null) {
                rollbackReservation(task, reservedUsageId);
            }
            log.error("任务调度异常 taskId={}", task.getId(), e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public List<TaskInstance> scanPendingTasks(int limit) {
        // 1) 参数保护：limit <= 0 时返回空列表；limit 过大时做上限截断（如 100/500）。
        if(limit <= 0){
            return Collections.emptyList();
        }
        if (limit > BATCH_SIZE) {
            limit = BATCH_SIZE;
        }
        // 2) 调用 mapper 查询：
        //    SELECT ... FROM task_instance
        //    WHERE status = 'PENDING'
        //    ORDER BY submit_time ASC
        //    LIMIT #{limit}
        try {
            // 3) 附带任务可用性过滤（仅保留启用任务），避免无效调度占用调度周期。
            return taskInstanceMapper.selectPendingTasks(limit);
        } catch (Exception e) {
            // 4) 查询异常时记录错误并返回空列表，避免打断 scheduleLoop。
            log.error("扫描待调度任务失败 limit={}", limit, e);
            return Collections.emptyList();
        }
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

    //选择节点（目前是简单轮询）
    private ResourceNode selectNode(TaskInstance task) {
        List<ResourceNode> onlineNodes = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, "ONLINE")
                        .orderByAsc(ResourceNode::getId)
        );
        if (onlineNodes == null || onlineNodes.isEmpty()) {
            return null;
        }

        // FIFO调度器：简单选择第一个节点
        // 后续P3-4会实现Best Fit算法
        return onlineNodes.get(0);
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
                new ReleaseResourceRequest(task.getId(), reservedUsageId, "FAILED", "scheduler rollback")
        );
        if (!releaseResult.isSuccess()) {
            log.error("资源回滚失败 taskId={} usageId={} reason={}", task.getId(), reservedUsageId, releaseResult.getMessage());
        }
    }

    private boolean submitToExecutor(TaskInstance task, ResourceNode node) {
        // P3-2 当前阶段仅完成调度状态流转，执行器集成在后续阶段接入。
        log.info("任务已进入RUNNING并绑定节点，执行器提交占位 taskId={} nodeId={}", task.getId(), node.getId());
        return true;
    }

    //这里是在调度过程中如果提交执行器失败了，需要回滚之前的状态更新和资源预留，确保系统状态的一致性和资源的正确释放。
    private void rollbackAfterDispatchFailure(TaskInstance task, Long reservedUsageId) {
        task.setStatus(TaskInstanceStatuses.PENDING);
        task.setResourceNodeId(null);
        task.setStartTime(null);
        task.setScheduledTime(null);
        taskInstanceMapper.updateById(task);
        rollbackReservation(task, reservedUsageId);
    }

    private ResourceRequirement parseRequirement(String json) {
        if (json == null || json.isBlank()) {
            return new ResourceRequirement();
        }
        try {
            return objectMapper.readValue(json, ResourceRequirement.class);
        } catch (Exception e) {
            log.warn("资源需求JSON解析失败，按0需求处理 json={}", json, e);
            return new ResourceRequirement();
        }
    }

    private int toNonNegativeInt(Number value) {
        if (value == null) {
            return 0;
        }
        return Math.max(value.intValue(), 0);
    }
}
