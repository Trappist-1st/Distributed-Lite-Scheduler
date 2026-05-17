package com.imperium.distributed_lite_scheduler_v1.service.scheduler.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.PrioritySchedulerService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 优先级调度器实现骨架（P3-3）。
 */
@Slf4j
@Service
public class PrioritySchedulerServiceImpl implements PrioritySchedulerService {

    private static final double PRIORITY_WEIGHT = 10.0;
    private static final double AGING_WEIGHT = 0.1;
    private static final int BATCH_SIZE = 100;
    private static final int TASK_LOCK_WAIT_SECONDS = 1;
    private static final int TASK_LOCK_LEASE_SECONDS = 10;

    private final TaskInstanceMapper taskInstanceMapper;
    private final ResourceNodeMapper resourceNodeMapper;
    private final ResourceSlotService resourceSlotService;
    private final ResourceQuotaService resourceQuotaService;
    private final RedissonClient redissonClient;
    private final TaskDispatchService taskDispatchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    //使用volatile关键字是保证有序性和可见性，确保并发环境下对isLeader的修改能被其他线程及时看到，避免多个实例同时认为自己是Leader。
    private volatile boolean isLeader = false;

    public PrioritySchedulerServiceImpl(TaskInstanceMapper taskInstanceMapper,
                                        ResourceNodeMapper resourceNodeMapper,
                                        ResourceSlotService resourceSlotService,
                                        ResourceQuotaService resourceQuotaService,
                                        RedissonClient redissonClient,
                                        TaskDispatchService taskDispatchService) {
        this.taskInstanceMapper = taskInstanceMapper;
        this.resourceNodeMapper = resourceNodeMapper;
        this.resourceSlotService = resourceSlotService;
        this.resourceQuotaService = resourceQuotaService;
        this.redissonClient = redissonClient;
        this.taskDispatchService = taskDispatchService;
    }

    @Override
    public void scheduleLoop() {
        // Step 0: 记录调度周期开始时间，用于统计总耗时与调度延迟。
        long loopStart = System.currentTimeMillis();

        // Step 1: 复用 P3-2 的 Leader 选举逻辑（Redis 分布式锁）。
        // 目标：多实例场景下，同一时刻只允许一个调度器实例执行本轮优先级调度。
        if (!tryAcquireLeadership()) {
            log.debug("非Leader节点，跳过优先级调度周期");
            return;
        }

        try {
            // Step 2: 扫描 PENDING 任务并计算有效优先级。
            List<TaskWithPriority> tasks = scanPendingTasksWithPriority(BATCH_SIZE);
            if (tasks.isEmpty()) {
                log.debug("无待调度任务");
                return;
            }

            // Step 3: 按有效优先级顺序逐个调度。
            // 注：排序应尽量在 SQL 层完成（ORDER BY effective_priority DESC），
            // 应用层仅顺序消费，减少内存重排与多实例一致性问题。
            int successCount = 0;
            int skipCount = 0;
            for (TaskWithPriority task : tasks) {
                boolean scheduled = scheduleTask(task.getTask());
                if (scheduled) {
                    successCount++;
                } else {
                    skipCount++;
                }
            }

            // Step 4: 记录关键日志与监控指标。
            // 关键指标建议包括：
            // - 本轮任务总数/成功数/跳过数
            // - 本轮耗时
            // - 任务基础优先级与有效优先级分布
            // - 任务等待时长（waitingMinutes）分位值
            long elapsed = System.currentTimeMillis() - loopStart;
            log.info("优先级调度周期完成 total={} success={} skip={} elapsedMs={}",
                    tasks.size(), successCount, skipCount, elapsed);
        } catch (Exception e) {
            //Step 5: 异常兜底。
            // 任意单轮异常不能中断后续调度周期，catch 后仅记录日志并返回。
            log.error("优先级调度周期异常", e);
        }
    }

    //依然是Leader选举，P3-2 的实现可复用，确保同一时刻只有一个实例执行调度逻辑
    //具体使用Redis分布式锁，key 设计为 "scheduler:priority:leader-lock"，过期时间设置为调度周期的合理上限（如 30 秒），并在 scheduleLoop 结束时释放锁。
    private boolean tryAcquireLeadership() {
        //使用Redisson分布式锁
        String lockkey = "scheduler:priority:leader-lock";
        RLock lock = redissonClient.getLock(lockkey);

        //尝试获取锁，设置合理的等待时间和锁持有时间，避免死锁和长时间占用。
        try{
            boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if(acquired && !isLeader){
                isLeader = true;
                log.info("成功获取领导权，成为本轮优先级调度器");
            }
            return acquired;
        }catch (Exception e){
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public List<TaskWithPriority> scanPendingTasksWithPriority(int limit) {
        try {
            // Step 1: 参数保护。
            // - limit <= 0 返回空集合
            // - limit 过大时做上限截断（如 BATCH_SIZE）
            if (limit <= 0) {
                return Collections.emptyList();
            }
            limit = Math.min(limit, BATCH_SIZE);

            // Step 2: 通过 Mapper 执行“带计算列”的查询。
            // 查询应返回：
            // - TaskInstance 基础字段
            // - priorityBonus（租户加成）
            // - waitingMinutes（等待分钟数）
            // - effectivePriority（按公式计算）
            // 公式（设计稿）：
            // effectivePriority = basePriority * PRIORITY_WEIGHT
            //                   + waitingMinutes * AGING_WEIGHT
            //                   + tenantBonus
            // List<TaskWithPriority> rows = taskInstanceMapper.selectPendingTasksWithPriority(
            //         limit, PRIORITY_WEIGHT, AGING_WEIGHT
            // );
            List<TaskWithPriority> tasks = taskInstanceMapper.selectPendingTasksWithPriority(
                    limit, PRIORITY_WEIGHT, AGING_WEIGHT
            );

            if (tasks == null || tasks.isEmpty()) {
                return Collections.emptyList();
            }

            // Step 3: 结果规范化。
            // - 对 null 的 effectivePriority / waitingMinutes / priorityBonus 做兜底值
            // - 对 task 为空或 task.id 为空的数据做过滤，防止后续调度 NPE
            // - 如 SQL 未排序，可在应用层补一次降序排序（仅兜底，不作为主路径）
            tasks.removeIf(item -> item == null || item.getTask() == null || item.getTask().getId() == null);
            if (tasks.isEmpty()) {
                return Collections.emptyList();
            }
            for (TaskWithPriority item : tasks) {
                if (item.getEffectivePriority() == null) {
                    item.setEffectivePriority(0.0d);
                }
                if (item.getWaitingMinutes() == null) {
                    item.setWaitingMinutes(0L);
                }
                if (item.getPriorityBonus() == null) {
                    item.setPriorityBonus(0);
                }
            }
            tasks.sort((a, b) -> Double.compare(
                    b.getEffectivePriority() == null ? 0.0d : b.getEffectivePriority(),
                    a.getEffectivePriority() == null ? 0.0d : a.getEffectivePriority()
            ));
            return tasks;
        } catch (Exception e) {
            // Step 4: 异常处理。
            // 查询异常时记录错误并返回空列表，避免打断 scheduleLoop。
            log.error("查询优先级待调度任务失败 limit={}", limit, e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean scheduleTask(TaskInstance taskInstance) {
        // P3-3 核心原则：
        // 单任务调度逻辑与 P3-2 FIFO 调度器保持一致，差异仅在“任务选取顺序”。
        // 即：Priority 调度器负责“先挑谁”，真正“怎么调度”沿用 FIFO 单任务流程。
        // P3-3 Step 1: 参数校验。
        // taskInstance 为空或 id 为空时直接返回 false。
        if(taskInstance == null || taskInstance.getId() == null){
            log.warn("无效的任务实例，无法调度 taskInstance={}", taskInstance);
            return false;
        }
        // P3-3 Step 2: 任务级分布式锁。
        // key = "task:schedule:lock:{taskId}"
        // tryLock 失败直接返回 false（避免重复调度）。
        String lockKey = "task:schedule:lock:" + taskInstance.getId();
        RLock taskLock = redissonClient.getLock(lockKey);
        Long reservedUsageId = null;
        try {
            boolean lockAcquired = taskLock.tryLock(TASK_LOCK_WAIT_SECONDS, TASK_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!lockAcquired) {
                log.debug("任务调度锁获取失败，跳过 taskId={}", taskInstance.getId());
                return false;
            }

        // TODO P3-3 Step 3: 双重检查状态。
        // 从 DB 查询最新 task_instance，确保状态仍为 PENDING。
        // 若已被其他实例更新为 RUNNING/SUCCESS/FAILED，则返回 false。
            TaskInstance latest = taskInstanceMapper.selectById(taskInstance.getId());
            if (latest == null) {
                log.warn("任务不存在，跳过调度 taskId={}", taskInstance.getId());
                return false;
            }
            if (!TaskInstanceStatus.PENDING.getCode().equals(latest.getStatus())) {
                log.debug("任务状态非PENDING，跳过 taskId={} status={}", latest.getId(), latest.getStatus());
                return false;
            }

        // TODO P3-3 Step 4: 配额检查。
        // 基于 tenantId + resourceRequirement 调用配额服务。
        // 配额不足返回 false（保持 PENDING，等待后续轮次/aging 提升）。
            if (!checkQuota(latest)) {
                log.debug("任务配额检查未通过，跳过 taskId={}", latest.getId());
                return false;
            }

        // P3-3 Step 5: 选择 ONLINE 节点并预留资源。
        // - 无可用节点：返回 false
        // - 预留失败：返回 false
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

        // P3-3 Step 6: 原子状态流转（乐观锁）。
        // PENDING -> RUNNING
        // 同时写入 resourceNodeId/startTime/scheduledTime/updatedAt 等字段。
        // 更新失败（并发冲突）时，必须释放已预留资源并返回 false。
            LocalDateTime now = LocalDateTime.now();
            int updated = taskInstanceMapper.updateStatusWithVersion(
                    latest.getId(),
                    TaskInstanceStatus.PENDING.getCode(),
                    TaskInstanceStatus.RUNNING.getCode(),
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

        // P3-3 Step 7: 提交执行器。
        // 若执行器提交失败：
        // - 回滚任务状态（RUNNING -> PENDING 或 FAILED，按当前策略）
        // - 释放资源
        // - 记录失败原因并返回 false
            if (!submitToExecutor(latest, selectedNode)) {
                rollbackAfterDispatchFailure(latest, reservedUsageId);
                log.warn("提交执行器失败，已回滚 taskId={} nodeId={}", latest.getId(), selectedNode.getId());
                return false;
            }

        // P3-3 Step 8: 成功路径与 finally。
        // - 记录成功日志（建议打印 basePriority/effectivePriority/waitingMinutes）
        // - 返回 true
        // - finally 里释放任务级分布式锁
            log.info("优先级任务调度成功 taskId={} nodeId={} basePriority={}",
                    latest.getId(), selectedNode.getId(), latest.getPriority());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("任务调度被中断 taskId={}", taskInstance.getId(), e);
            return false;
        } catch (Exception e) {
            if (reservedUsageId != null) {
                rollbackReservation(taskInstance, reservedUsageId);
            }
            log.error("优先级任务调度异常 taskId={}", taskInstance.getId(), e);
            return false;
        } finally {
            if (taskLock.isHeldByCurrentThread()) {
                taskLock.unlock();
            }
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

    private ResourceNode selectNode(TaskInstance task) {
        List<ResourceNode> onlineNodes = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, "ONLINE")
                        .orderByAsc(ResourceNode::getId)
        );
        if (onlineNodes == null || onlineNodes.isEmpty()) {
            return null;
        }
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
    
    //在调度失败时，把已经预留的资源释放掉，防止资源泄漏。
    private void rollbackReservation(TaskInstance task, Long reservedUsageId) {
        if (reservedUsageId == null || reservedUsageId <= 0) {
            return;
        }
        Result<Void> releaseResult = resourceSlotService.release(
                new ReleaseResourceRequest(task.getId(), reservedUsageId, "FAILED", "priority scheduler rollback")
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
