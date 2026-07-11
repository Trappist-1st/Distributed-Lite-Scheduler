package com.imperium.distributed_lite_scheduler_v1.service.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在本地线程池中同步执行单个任务实例并上报结果。
 *
 * <h3>心跳机制</h3>
 * <p>任务开始执行时，启动一个独立的心跳调度线程，每 {@value #HEARTBEAT_INTERVAL_SECONDS} 秒
 * 向 DB 更新 {@code last_heartbeat_at}。任务结束（正常/异常）后立即停止心跳。
 *
 * <p>若 {@link TaskHeartbeatService#beat} 返回 false（任务已被外部标记为终态，
 * 例如 NodeHeartbeatWatchdog 检测到节点掉线后已强制 FAILED），当前执行线程
 * 会通过 {@link AtomicBoolean} 协作式停止，防止"僵尸进程继续写结果"污染状态机。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalTaskRunner {

    /** 心跳上报间隔（秒）。NodeHeartbeatWatchdog 的超时阈值应为此值的 3 倍，留有余量。 */
    static final int HEARTBEAT_INTERVAL_SECONDS = 10;

    private final RunSpecBuilder runSpecBuilder;
    private final TaskTypeExecutorRegistry executorRegistry;
    private final TaskExecutionReporter executionReporter;
    private final TaskHeartbeatService heartbeatService;

    public void run(Long taskInstanceId, Long resourceNodeId) {
        log.info("开始执行任务实例 taskInstanceId={} nodeId={}", taskInstanceId, resourceNodeId);

        AtomicBoolean externallyTerminated = new AtomicBoolean(false);
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + taskInstanceId);
            t.setDaemon(true);
            return t;
        });

        // 立即发送第一次心跳，此后每 HEARTBEAT_INTERVAL_SECONDS 秒一次
        ScheduledFuture<?> heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            boolean alive = heartbeatService.beat(taskInstanceId);
            if (!alive) {
                // 任务已被外部标记为终态（宕机恢复路径强制 FAILED），协作式通知执行线程停止
                externallyTerminated.set(true);
            }
        }, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        try {
            RunSpec runSpec = runSpecBuilder.build(taskInstanceId, resourceNodeId);
            TaskTypeExecutor executor = executorRegistry.resolve(runSpec.getTaskType()).orElse(null);
            if (executor == null) {
                executionReporter.reportConfigurationError(
                        taskInstanceId,
                        "不支持的任务类型: " + runSpec.getTaskType());
                return;
            }

            ExecutionResult result = executor.execute(runSpec);

            // 执行完成后检查任务是否已被外部终止
            // 若是，不上报结果（外部已写 FAILED，不能再改回 SUCCESS）
            if (externallyTerminated.get()) {
                log.warn("任务已被外部终止（宕机恢复），忽略本地执行结果 taskInstanceId={}", taskInstanceId);
                return;
            }

            executionReporter.report(result, taskInstanceId);
        } catch (Exception e) {
            if (externallyTerminated.get()) {
                log.warn("任务已被外部终止，忽略本地异常 taskInstanceId={}", taskInstanceId, e);
                return;
            }
            log.error("任务执行异常 taskInstanceId={}", taskInstanceId, e);
            executionReporter.reportFailure(taskInstanceId, -1, e.getMessage());
        } finally {
            heartbeatFuture.cancel(false);
            heartbeatScheduler.shutdownNow();
            log.debug("心跳调度已停止 taskInstanceId={}", taskInstanceId);
        }
    }
}
