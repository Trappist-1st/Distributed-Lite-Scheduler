package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceUsageMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceUsage;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.impl.WorkflowLayerDispatchFacade;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统级对账/恢复 Worker（Reconciliation Worker）。
 *
 * <p>设计思想：不相信任何单点的状态汇报，定期主动扫描 DB，把系统从各种中间状态拉回一致性。
 * 这是继 Lease TTL 之后的最终兜底手段，覆盖其他机制未处理的 edge case。
 *
 * <p>处理三类异常状态：
 * <ol>
 *   <li><b>孤儿 RESERVED 资源</b>：reserve() 事务提交后 JVM crash，task_instance 未转为
 *       RUNNING，导致任务卡死 PENDING 且资源永久占用。清理方式：释放 usage，重置 task_instance。</li>
 *   <li><b>卡死 RUNNING task_instance</b>：超出任务定义 timeout 仍未终态（TaskTimeoutWatchdog
 *       的补充兜底，覆盖 Watchdog 本身宕机的场景）。</li>
 *   <li><b>卡死 RUNNING workflow_instance</b>：所有子任务均已终态但工作流未推进（典型原因：
 *       Redis Stream 消费者宕机导致 DAG 层推进事件丢失）。</li>
 * </ol>
 *
 * <p>并发安全：通过 {@link SchedulerLeaderElection} 确保只有 Leader 节点执行对账，
 * 避免多实例重复修复产生干扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationWorker {

    private static final int SCAN_LIMIT = 50;

    private final ResourceUsageMapper resourceUsageMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    private final ResourceSlotService resourceSlotService;
    private final TaskInstanceService taskInstanceService;
    private final WorkflowLayerDispatchFacade workflowLayerDispatchFacade;
    private final SchedulerLeaderElection schedulerLeaderElection;
    private final TaskRetryService taskRetryService;

    /**
     * 每 60 秒执行一次对账扫描（仅 Leader 节点执行）。
     * 独立申请 Leader 锁，不依赖调度主循环的瞬时锁状态。
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        boolean executed = schedulerLeaderElection.executeIfLeader(() -> {
            log.debug("开始对账扫描");
            recoverOrphanedReservations();
            recoverTimedOutRunningTasks();
            recoverStuckWorkflowInstances();
            log.debug("对账扫描完成");
        });
        if (!executed) {
            log.debug("非 Leader 节点，跳过对账扫描");
        }
    }

    // -------------------------------------------------------------------------
    // 扫描一：孤儿 RESERVED 资源流水
    // -------------------------------------------------------------------------

    /**
     * 查找 status=RESERVED 但对应 task_instance 不处于 RUNNING 的流水。
     * 说明：这些 usage 是 reserve() 提交后、updateStatusWithVersion() 执行前发生 crash 的产物。
     * 修复：释放 usage（恢复槽位和配额），并将 task_instance 的 resourceNodeId 清空，
     * 使任务重新进入可调度状态。
     */
    private void recoverOrphanedReservations() {
        try {
            List<ResourceUsage> orphaned = resourceUsageMapper.selectOrphanedReserved(SCAN_LIMIT);
            if (orphaned.isEmpty()) {
                return;
            }
            log.info("发现孤儿 RESERVED 资源流水 count={}", orphaned.size());
            for (ResourceUsage usage : orphaned) {
                try {
                    // 释放资源（回补槽位 + 配额）
                    resourceSlotService.releaseForTaskInstanceSystem(
                            usage.getTaskInstanceId(), "reconciliation-orphan-reserved");

                    // 清除 task_instance 上的 resourceNodeId 绑定，使其重新可被调度
                    if (usage.getTaskInstanceId() != null) {
                        taskInstanceMapper.update(null,
                                new LambdaUpdateWrapper<TaskInstance>()
                                        .eq(TaskInstance::getId, usage.getTaskInstanceId())
                                        .eq(TaskInstance::getStatus, TaskInstanceStatus.PENDING.getCode())
                                        .set(TaskInstance::getResourceNodeId, null));
                    }

                    log.info("已修复孤儿 RESERVED 流水 usageId={} taskInstanceId={}",
                            usage.getId(), usage.getTaskInstanceId());
                } catch (Exception e) {
                    log.error("修复孤儿 RESERVED 流水失败 usageId={}", usage.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("扫描孤儿 RESERVED 流水异常", e);
        }
    }

    // -------------------------------------------------------------------------
    // 扫描二：超时的 RUNNING task_instance
    // -------------------------------------------------------------------------

    /**
     * {@link com.imperium.distributed_lite_scheduler_v1.service.executor.watchdog.TaskTimeoutWatchdog}
     * 的兜底补偿：扫描已超过 task.timeout_seconds 仍为 RUNNING 的实例。
     *
     * <p>职责划分：
     * <ul>
     *   <li>{@code TaskTimeoutWatchdog}（30s）：主要路径，同时取消执行中的任务线程</li>
     *   <li>本方法（60s）：兜底路径，覆盖 Watchdog 本身宕机期间积压的超时任务</li>
     * </ul>
     * 二者均经状态机 {@code transitionStatus(RUNNING → TIMEOUT)} 处理，
     * MySQL 乐观锁（version CAS）保证同一任务只会被一个路径成功流转，无重复处理问题。
     */
    private void recoverTimedOutRunningTasks() {
        try {
            List<TaskInstance> timedOut = taskInstanceMapper.selectTimedOutRunningTasks(SCAN_LIMIT);
            if (timedOut.isEmpty()) {
                return;
            }
            log.info("对账发现超时 RUNNING 任务 count={}", timedOut.size());
            for (TaskInstance ti : timedOut) {
                try {
                    InternalTaskInstanceStatusTransitionRequest request =
                            new InternalTaskInstanceStatusTransitionRequest(
                                    TaskInstanceStatus.RUNNING.getCode(),
                                    TaskInstanceStatus.TIMEOUT.getCode(),
                                    "RECONCILIATION",
                                    "对账超时恢复",
                                    null,
                                    -1,
                                    "reconciliation: task exceeded timeout");

                    Result<TaskInstance> result = taskInstanceService.transitionStatus(ti.getId(), request);
                    if (result.isSuccess()) {
                        taskRetryService.retryIfNeeded(ti, "task timeout exceeded");
                        log.info("对账已修复超时 RUNNING 任务 taskInstanceId={}", ti.getId());
                    } else {
                        log.debug("对账超时流转未成功（已被其他路径处理）taskInstanceId={} message={}",
                                ti.getId(), result.getMessage());
                    }
                } catch (Exception e) {
                    log.error("对账修复超时 RUNNING 任务失败 taskInstanceId={}", ti.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("扫描超时 RUNNING 任务异常", e);
        }
    }

    // -------------------------------------------------------------------------
    // 扫描三：卡死的 RUNNING workflow_instance
    // -------------------------------------------------------------------------

    /**
     * 查找 status=RUNNING 但所有 workflow_task_instance 均已终态的工作流实例。
     * 典型原因：Redis Stream 消费者宕机（UUID 消费者名导致 PEL 孤儿）→ dispatchLayer 事件丢失。
     * 修复：重新触发 dispatchLayer，推进 DAG 到下一层或标记工作流完成。
     */
    private void recoverStuckWorkflowInstances() {
        try {
            List<WorkflowInstance> runningInstances =
                    workflowInstanceMapper.selectRunningInstances(SCAN_LIMIT);
            if (runningInstances.isEmpty()) {
                return;
            }
            for (WorkflowInstance wfInstance : runningInstances) {
                try {
                    checkAndRecoverWorkflowInstance(wfInstance);
                } catch (Exception e) {
                    log.error("检查工作流实例异常 workflowInstanceId={}", wfInstance.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("扫描卡死工作流实例异常", e);
        }
    }

    private void checkAndRecoverWorkflowInstance(WorkflowInstance wfInstance) throws Exception {
        List<WorkflowTaskInstance> allTasks =
                workflowTaskInstanceMapper.selectByInstanceId(wfInstance.getId());
        if (allTasks.isEmpty()) {
            return;
        }

        // 按层分析：找到最大的"全部终态"层，如果其下一层有 PENDING 任务则说明推进卡死了
        int maxTerminalLayer = -1;
        int maxLayer = allTasks.stream()
                .mapToInt(t -> t.getLayerIndex() == null ? 0 : t.getLayerIndex())
                .max().orElse(0);

        for (int layer = 0; layer <= maxLayer; layer++) {
            final int currentLayer = layer;
            List<WorkflowTaskInstance> layerTasks = allTasks.stream()
                    .filter(t -> t.getLayerIndex() != null && t.getLayerIndex() == currentLayer)
                    .toList();
            boolean allTerminal = layerTasks.stream().allMatch(this::isTerminal);
            if (allTerminal) {
                maxTerminalLayer = layer;
            } else {
                break;
            }
        }

        if (maxTerminalLayer < 0) {
            return;
        }

        // 如果所有层都终态了，交给 dispatchLayer 来标记工作流完成
        int nextLayer = maxTerminalLayer + 1;
        if (nextLayer > maxLayer) {
            // 所有层已完成但工作流仍是 RUNNING → 卡死，触发推进
            log.info("检测到卡死工作流（所有层已终态）workflowInstanceId={} maxLayer={}", wfInstance.getId(), maxLayer);
            workflowLayerDispatchFacade.onWorkflowTaskTerminated(wfInstance.getId(), maxTerminalLayer);
        } else {
            // 下一层存在 PENDING 任务 → 层推进卡死，重新 dispatch
            final int checkLayer = nextLayer;
            boolean nextLayerHasPending = allTasks.stream()
                    .filter(t -> t.getLayerIndex() != null && t.getLayerIndex() == checkLayer)
                    .anyMatch(t -> TaskInstanceStatus.PENDING.matches(t.getStatus()));
            if (nextLayerHasPending) {
                log.info("检测到卡死工作流（下一层未触发）workflowInstanceId={} stuckAtLayer={}",
                        wfInstance.getId(), nextLayer);
                workflowLayerDispatchFacade.onWorkflowTaskTerminated(wfInstance.getId(), maxTerminalLayer);
            }
        }
    }

    private boolean isTerminal(WorkflowTaskInstance task) {
        try {
            return TaskInstanceStatus.fromCode(task.getStatus()).isTerminal();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
