package com.imperium.distributed_lite_scheduler_v1.service.scheduler;

import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点宕机检测与任务恢复 Watchdog。
 *
 * <h3>解决的核心分布式问题</h3>
 * <p>面试官的问题：<em>"如果执行节点在任务执行到一半时宕机，该任务怎么保证能正常运行？"</em>
 *
 * <p>这是分布式系统中"部分失败（partial failure）"场景的典型问题。
 * 节点宕机是不可避免的，系统需要：
 * <ol>
 *   <li><b>及时发现</b>：不能无限等 timeout，必须有主动探活机制（心跳 + Watchdog）</li>
 *   <li><b>正确恢复</b>：将节点上的任务标记为失败，释放资源，重新放回调度队列</li>
 *   <li><b>防止僵尸写入</b>：已宕机节点可能在网络恢复后"复活"并尝试写回结果，
 *       通过状态机乐观锁（MySQL CAS version 检查）拒绝过期写入</li>
 * </ol>
 *
 * <h3>工作原理</h3>
 * <pre>
 * 每 15 秒执行一次（仅 Leader 节点）：
 *
 * 1. 扫描 resource_node：
 *    last_heartbeat_time < NOW() - 30s  AND  status = 'ONLINE'
 *    → 判定为疑似宕机节点
 *
 * 2. 对每个疑似宕机节点：
 *    a. CAS 更新 status = 'OFFLINE'（乐观锁，防并发重复处理）
 *    b. 查询该节点上所有 RUNNING 任务
 *    c. 对每个 RUNNING 任务：
 *       - 经状态机 transitionStatus(RUNNING → FAILED)（自动释放资源 + 发布完成事件）
 *       - 触发自动重试（若 retry_count < task.retry_times）
 *
 * 3. 扫描 task_instance（兜底）：
 *    last_heartbeat_at < NOW() - 30s  AND  status = 'RUNNING'
 *    → 处理节点检测遗漏的"僵尸任务"（如进程 hang 但节点本身还活着）
 * </pre>
 *
 * <h3>Leader 选举说明</h3>
 * <p>本 Watchdog 通过 {@link SchedulerLeaderElection#executeIfLeader(Runnable)} 独立申请 Leader 锁，
 * 与调度主循环的 Leader 锁互不干扰（均使用同一 Redis key，通过 CAS 保证同一时刻只有一个实例执行）。
 * MySQL 乐观锁（version CAS）是双 Leader 的最终安全网。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeHeartbeatWatchdog {

    /** 节点心跳超时阈值（秒）。若节点 last_heartbeat_time 超过此时间未更新，视为宕机。 */
    static final int NODE_HEARTBEAT_TIMEOUT_SECONDS = 30;

    /** 僵尸任务心跳超时阈值（秒）。与节点阈值相同，保持一致。 */
    static final int TASK_HEARTBEAT_TIMEOUT_SECONDS = 30;

    private static final int SCAN_LIMIT = 20;

    private final ResourceNodeMapper resourceNodeMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskInstanceService taskInstanceService;
    private final TaskRetryService taskRetryService;
    private final SchedulerLeaderElection schedulerLeaderElection;

    /**
     * 每 15 秒检测一次（仅 Leader 节点执行）。
     * 独立申请 Leader 锁，不依赖调度主循环的瞬时锁状态。
     */
    @Scheduled(fixedDelay = 15_000)
    public void watchNodes() {
        boolean executed = schedulerLeaderElection.executeIfLeader(() -> {
            detectDeadNodes();
            detectZombieTasks();
        });
        if (!executed) {
            log.debug("非 Leader 节点，跳过节点心跳检测");
        }
    }

    // -------------------------------------------------------------------------
    // 步骤一：检测宕机节点
    // -------------------------------------------------------------------------

    private void detectDeadNodes() {
        LocalDateTime heartbeatDeadline = LocalDateTime.now()
                .minusSeconds(NODE_HEARTBEAT_TIMEOUT_SECONDS);

        List<ResourceNode> deadNodes = resourceNodeMapper.selectDeadNodes(heartbeatDeadline, SCAN_LIMIT);
        if (deadNodes.isEmpty()) {
            return;
        }

        log.warn("检测到疑似宕机节点 count={}", deadNodes.size());
        for (ResourceNode node : deadNodes) {
            recoverDeadNode(node);
        }
    }

    private void recoverDeadNode(ResourceNode node) {
        int updated = resourceNodeMapper.markOffline(node.getId());
        if (updated == 0) {
            log.debug("节点已被其他线程标记为 OFFLINE，跳过 nodeId={}", node.getId());
            return;
        }

        log.warn("节点已标记为 OFFLINE nodeId={} nodeName={} lastHeartbeat={}",
                node.getId(), node.getNodeName(), node.getLastHeartbeatTime());

        List<TaskInstance> runningTasks = taskInstanceMapper.selectRunningByNodeId(node.getId());
        if (runningTasks.isEmpty()) {
            log.info("宕机节点上没有 RUNNING 任务 nodeId={}", node.getId());
            return;
        }

        log.warn("宕机节点上有 {} 个 RUNNING 任务需要恢复 nodeId={}", runningTasks.size(), node.getId());
        for (TaskInstance task : runningTasks) {
            recoverTaskFromDeadNode(task, "node-crash: nodeId=" + node.getId()
                    + " nodeName=" + node.getNodeName());
        }
    }

    // -------------------------------------------------------------------------
    // 步骤二：检测僵尸任务（节点存活但任务线程 hang 死）
    // -------------------------------------------------------------------------

    private void detectZombieTasks() {
        LocalDateTime heartbeatDeadline = LocalDateTime.now()
                .minusSeconds(TASK_HEARTBEAT_TIMEOUT_SECONDS);

        List<TaskInstance> zombieTasks = taskInstanceMapper.selectZombieRunningTasks(
                heartbeatDeadline, SCAN_LIMIT);
        if (zombieTasks.isEmpty()) {
            return;
        }

        log.warn("检测到僵尸任务（心跳超时） count={}", zombieTasks.size());
        for (TaskInstance task : zombieTasks) {
            recoverTaskFromDeadNode(task, "zombie-task: heartbeat expired");
        }
    }

    // -------------------------------------------------------------------------
    // 核心恢复逻辑
    // -------------------------------------------------------------------------

    /**
     * 将 RUNNING 任务经状态机流转为 FAILED，自动触发资源释放、审计日志和 Redis Stream 完成事件。
     *
     * <p>状态机的乐观锁（version CAS）防止"复活"节点在网络恢复后写回结果（Fencing 语义）。
     * 若 CAS 失败说明任务状态已被其他路径更新，直接跳过。
     */
    private void recoverTaskFromDeadNode(TaskInstance task, String failReason) {
        try {
            InternalTaskInstanceStatusTransitionRequest request =
                    new InternalTaskInstanceStatusTransitionRequest(
                            TaskInstanceStatus.RUNNING.getCode(),
                            TaskInstanceStatus.FAILED.getCode(),
                            "WATCHDOG",
                            "节点宕机恢复",
                            null,
                            -1,
                            "watchdog: " + failReason);

            Result<TaskInstance> result = taskInstanceService.transitionStatus(task.getId(), request);
            if (!result.isSuccess()) {
                log.debug("Watchdog 状态流转未成功（已被其他路径处理）taskInstanceId={} message={}",
                        task.getId(), result.getMessage());
                return;
            }

            log.info("Watchdog 已将宕机任务标记为 FAILED taskInstanceId={} reason={}", task.getId(), failReason);

            try {
                boolean retried = taskRetryService.retryIfNeeded(task, failReason);
                if (!retried) {
                    log.info("任务达到重试上限或不允许重试 taskInstanceId={}", task.getId());
                }
            } catch (Exception e) {
                log.error("触发任务重试失败 taskInstanceId={}", task.getId(), e);
            }

        } catch (Exception e) {
            log.error("Watchdog 恢复任务失败 taskInstanceId={}", task.getId(), e);
        }
    }
}
