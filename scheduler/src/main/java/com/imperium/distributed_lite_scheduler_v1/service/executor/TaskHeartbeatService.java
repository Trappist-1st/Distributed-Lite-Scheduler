package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 执行器心跳服务：由 {@link LocalTaskRunner} 在任务执行期间定期调用，
 * 向调度器汇报"我还活着，任务还在跑"。
 *
 * <h3>为什么需要任务级心跳？</h3>
 * <p>超时配置（{@code task.timeout_seconds}）只能处理"任务本身运行超时"的情况，
 * 无法处理"执行器进程崩溃（JVM crash / OOM kill / 宿主机断电）"。
 * 进程崩溃后 {@code task_instance.status} 永远停在 RUNNING，资源也永远不会释放。
 *
 * <p>引入心跳后，{@link NodeHeartbeatWatchdog} 和 {@link com.imperium.distributed_lite_scheduler_v1.service.scheduler.ReconciliationWorker}
 * 可以通过 {@code last_heartbeat_at} 字段检测"僵尸任务"：
 * 心跳超过 2 倍心跳间隔（默认 30 秒）未更新 → 视为宕机 → 标记 FAILED → 触发自动重试。
 *
 * <h3>与节点级心跳的区别</h3>
 * <ul>
 *   <li>节点级心跳（{@code resource_node.last_heartbeat_time}）：资源节点整体是否存活</li>
 *   <li>任务级心跳（{@code task_instance.last_heartbeat_at}）：具体任务线程是否还在执行</li>
 * </ul>
 * 两者相互补充：节点死了一般任务也死了，但任务也可能因单个线程 hang 死而心跳停止。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskHeartbeatService {

    private final TaskInstanceMapper taskInstanceMapper;

    /**
     * 更新指定任务实例的心跳时间（set last_heartbeat_at = NOW()）。
     *
     * @param taskInstanceId 任务实例 ID
     * @return true 表示更新成功（任务仍为 RUNNING）；
     *         false 表示任务已不在 RUNNING 状态（可能已被外部标记为 FAILED/TIMEOUT），
     *         调用方应停止心跳并终止执行。
     */
    public boolean beat(Long taskInstanceId) {
        try {
            int updated = taskInstanceMapper.updateHeartbeat(taskInstanceId);
            if (updated == 0) {
                log.warn("心跳更新失败：任务实例已不在 RUNNING 状态，执行器应停止 taskInstanceId={}", taskInstanceId);
                return false;
            }
            log.trace("心跳已更新 taskInstanceId={}", taskInstanceId);
            return true;
        } catch (Exception e) {
            log.error("心跳上报异常 taskInstanceId={}", taskInstanceId, e);
            return true; // 网络/DB 抖动时不中断执行，下次重试
        }
    }
}
