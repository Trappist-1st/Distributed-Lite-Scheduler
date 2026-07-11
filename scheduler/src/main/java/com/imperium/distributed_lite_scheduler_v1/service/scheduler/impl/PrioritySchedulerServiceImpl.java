package com.imperium.distributed_lite_scheduler_v1.service.scheduler.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskWithPriority;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskDispatchService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.AbstractSchedulerService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.PrioritySchedulerService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.TaskScheduleLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 优先级调度器实现：按优先级 + aging 公式排序，选择第一个 ONLINE 节点。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "scheduler.strategy", havingValue = "priority")
public class PrioritySchedulerServiceImpl extends AbstractSchedulerService implements PrioritySchedulerService {

    private static final double PRIORITY_WEIGHT = 10.0;
    private static final double AGING_WEIGHT = 0.1;
    private static final int BATCH_SIZE = 100;

    public PrioritySchedulerServiceImpl(
            TaskInstanceMapper taskInstanceMapper,
            ResourceNodeMapper resourceNodeMapper,
            ResourceSlotService resourceSlotService,
            ResourceQuotaService resourceQuotaService,
            TaskScheduleLock taskScheduleLock,
            TaskDispatchService taskDispatchService) {
        super(taskInstanceMapper, resourceNodeMapper, resourceSlotService,
                resourceQuotaService, taskScheduleLock, taskDispatchService);
    }

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
            for (TaskWithPriority task : tasks) {
                if (scheduleTask(task.getTask())) successCount++;
                else skipCount++;
            }
            long elapsed = System.currentTimeMillis() - loopStart;
            log.info("优先级调度周期完成 total={} success={} skip={} elapsedMs={}",
                    tasks.size(), successCount, skipCount, elapsed);
        } catch (Exception e) {
            log.error("优先级调度周期异常", e);
        }
    }

    @Override
    public List<TaskWithPriority> scanPendingTasksWithPriority(int limit) {
        try {
            if (limit <= 0) return Collections.emptyList();
            limit = Math.min(limit, BATCH_SIZE);
            List<TaskWithPriority> tasks = taskInstanceMapper.selectPendingTasksWithPriority(
                    limit, PRIORITY_WEIGHT, AGING_WEIGHT);
            if (tasks == null || tasks.isEmpty()) return Collections.emptyList();
            tasks.removeIf(item -> item == null || item.getTask() == null || item.getTask().getId() == null);
            for (TaskWithPriority item : tasks) {
                if (item.getEffectivePriority() == null) item.setEffectivePriority(0.0d);
                if (item.getWaitingMinutes() == null) item.setWaitingMinutes(0L);
                if (item.getPriorityBonus() == null) item.setPriorityBonus(0);
            }
            tasks.sort((a, b) -> Double.compare(
                    b.getEffectivePriority() == null ? 0.0d : b.getEffectivePriority(),
                    a.getEffectivePriority() == null ? 0.0d : a.getEffectivePriority()));
            return tasks;
        } catch (Exception e) {
            log.error("查询优先级待调度任务失败 limit={}", limit, e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean scheduleTask(TaskInstance taskInstance) {
        if (taskInstance == null || taskInstance.getId() == null) {
            log.warn("无效的任务实例，无法调度 taskInstance={}", taskInstance);
            return false;
        }
        RLock taskLock = taskScheduleLock.lockFor(taskInstance.getId());
        Long reservedUsageId = null;
        try {
            if (!taskScheduleLock.tryAcquire(taskLock)) {
                log.debug("任务调度锁获取失败，跳过 taskId={}", taskInstance.getId());
                return false;
            }
            TaskInstance latest = taskInstanceMapper.selectById(taskInstance.getId());
            if (latest == null) {
                log.warn("任务不存在，跳过调度 taskId={}", taskInstance.getId());
                return false;
            }
            if (!TaskInstanceStatus.PENDING.getCode().equals(latest.getStatus())) {
                log.debug("任务状态非 PENDING，跳过 taskId={} status={}", latest.getId(), latest.getStatus());
                return false;
            }
            if (!checkQuota(latest)) {
                log.debug("任务配额检查未通过，跳过 taskId={}", latest.getId());
                return false;
            }
            ResourceNode selectedNode = selectNode();
            if (selectedNode == null) {
                log.debug("无可用 ONLINE 节点，跳过 taskId={}", latest.getId());
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
                    TaskInstanceStatus.PENDING.getCode(),
                    TaskInstanceStatus.RUNNING.getCode(),
                    latest.getVersion() == null ? 0 : latest.getVersion(),
                    selectedNode.getId(),
                    now,
                    now
            );
            if (updated != 1) {
                rollbackReservation(latest, reservedUsageId);
                log.warn("状态流转失败（并发冲突）taskId={} version={}", latest.getId(), latest.getVersion());
                return false;
            }
            if (!submitToExecutor(latest, selectedNode)) {
                rollbackAfterDispatchFailure(latest, reservedUsageId);
                log.warn("提交执行器失败，已回滚 taskId={} nodeId={}", latest.getId(), selectedNode.getId());
                return false;
            }
            log.info("优先级任务调度成功 taskId={} nodeId={} basePriority={}",
                    latest.getId(), selectedNode.getId(), latest.getPriority());
            return true;
        } catch (Exception e) {
            if (reservedUsageId != null) {
                rollbackReservation(taskInstance, reservedUsageId);
            }
            log.error("优先级任务调度异常 taskId={}", taskInstance.getId(), e);
            return false;
        } finally {
            taskScheduleLock.release(taskLock);
        }
    }

    private ResourceNode selectNode() {
        List<ResourceNode> onlineNodes = resourceNodeMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, "ONLINE")
                        .orderByAsc(ResourceNode::getId)
        );
        if (onlineNodes == null || onlineNodes.isEmpty()) return null;
        return onlineNodes.get(0);
    }
}
