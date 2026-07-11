package com.imperium.distributed_lite_scheduler_v1.service.scheduler.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceQuotaService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskDispatchService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.AbstractSchedulerService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.FifoSchedulerService;
import com.imperium.distributed_lite_scheduler_v1.service.scheduler.TaskScheduleLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * FIFO 调度器实现：按提交时间先进先出，选择第一个 ONLINE 节点。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "scheduler.strategy", havingValue = "fifo")
public class FifoSchedulerServiceImpl extends AbstractSchedulerService implements FifoSchedulerService {

    private static final int BATCH_SIZE = 100;

    public FifoSchedulerServiceImpl(
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
        log.info("开始 FIFO 调度周期");
        try {
            List<TaskInstance> pendingTasks = scanPendingTasks(BATCH_SIZE);
            if (pendingTasks.isEmpty()) {
                log.debug("无待调度任务");
                return;
            }
            int successCount = 0;
            int skipCount = 0;
            for (TaskInstance task : pendingTasks) {
                if (scheduleTask(task)) successCount++;
                else skipCount++;
            }
            long elapsed = System.currentTimeMillis() - loopStart;
            log.info("FIFO 调度周期完成 total={} success={} skip={} elapsedMs={}",
                    pendingTasks.size(), successCount, skipCount, elapsed);
        } catch (Exception e) {
            log.error("FIFO 调度周期异常", e);
        }
    }

    @Override
    public boolean scheduleTask(TaskInstance task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        RLock lock = taskScheduleLock.lockFor(task.getId());
        Long reservedUsageId = null;
        long startMs = System.currentTimeMillis();
        try {
            if (!taskScheduleLock.tryAcquire(lock)) {
                log.debug("任务调度锁获取失败，跳过 taskId={}", task.getId());
                return false;
            }
            TaskInstance latest = taskInstanceMapper.selectById(task.getId());
            if (latest == null) {
                log.warn("任务不存在，跳过调度 taskId={}", task.getId());
                return false;
            }
            if (!TaskInstanceStatus.PENDING.matches(latest.getStatus())) {
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
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("FIFO 任务调度成功 taskId={} nodeId={} elapsedMs={}",
                    latest.getId(), selectedNode.getId(), elapsed);
            return true;
        } catch (Exception e) {
            if (reservedUsageId != null) {
                rollbackReservation(task, reservedUsageId);
            }
            log.error("FIFO 任务调度异常 taskId={}", task.getId(), e);
            return false;
        } finally {
            taskScheduleLock.release(lock);
        }
    }

    @Override
    public List<TaskInstance> scanPendingTasks(int limit) {
        if (limit <= 0) return Collections.emptyList();
        limit = Math.min(limit, BATCH_SIZE);
        try {
            return taskInstanceMapper.selectPendingTasks(limit);
        } catch (Exception e) {
            log.error("扫描待调度任务失败 limit={}", limit, e);
            return Collections.emptyList();
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
