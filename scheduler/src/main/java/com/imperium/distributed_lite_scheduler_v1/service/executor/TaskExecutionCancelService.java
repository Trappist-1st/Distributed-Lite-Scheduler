package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.service.executor.runtime.RunningTaskRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 取消正在执行的任务（本机进程或远程 Worker）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionCancelService {

    private final RunningTaskRegistry runningTaskRegistry;
    private final TaskExecutorProperties taskExecutorProperties;
    private final ResourceNodeMapper resourceNodeMapper;
    private final WorkerHttpClient workerHttpClient;

    public void cancelRunningTask(Long taskInstanceId, Long resourceNodeId) {
        if (taskInstanceId == null) {
            return;
        }
        if (runningTaskRegistry.cancel(taskInstanceId)) {
            log.info("已取消本机运行任务 taskInstanceId={}", taskInstanceId);
            return;
        }
        if (!"remote".equalsIgnoreCase(taskExecutorProperties.getMode())) {
            return;
        }
        if (resourceNodeId == null) {
            log.debug("远程取消跳过：无 resourceNodeId taskInstanceId={}", taskInstanceId);
            return;
        }
        ResourceNode node = resourceNodeMapper.selectById(resourceNodeId);
        if (node == null) {
            log.warn("远程取消失败：节点不存在 nodeId={} taskInstanceId={}", resourceNodeId, taskInstanceId);
            return;
        }
        boolean cancelled = workerHttpClient.cancelRun(node, taskInstanceId);
        if (cancelled) {
            log.info("已向 Worker 发送取消请求 taskInstanceId={} nodeId={}", taskInstanceId, resourceNodeId);
        }
    }
}
