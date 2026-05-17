package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.model.dto.worker.WorkerRunRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Step2：通过 HTTP 将任务下发到远程 Worker 节点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "task.executor.mode", havingValue = "remote")
public class RemoteTaskDispatchService implements TaskDispatchService {

    private final TaskExecutorProperties properties;
    private final RunSpecBuilder runSpecBuilder;
    private final WorkerRunRequestFactory workerRunRequestFactory;
    private final WorkerHttpClient workerHttpClient;

    @Override
    public boolean dispatch(TaskInstance taskInstance, ResourceNode node) {
        if (!properties.isEnabled()) {
            log.warn("任务执行器未启用，拒绝提交 taskInstanceId={}", taskInstance.getId());
            return false;
        }
        if (node == null) {
            log.warn("远程执行缺少资源节点 taskInstanceId={}", taskInstance.getId());
            return false;
        }
        if (!StringUtils.hasText(properties.getSchedulerPublicBaseUrl())) {
            log.error("未配置 task.executor.scheduler-public-base-url，无法构造 Worker 回调地址");
            return false;
        }

        try {
            RunSpec runSpec = runSpecBuilder.build(taskInstance.getId(), node.getId());
            WorkerRunRequest request = workerRunRequestFactory.fromRunSpec(runSpec, node);
            boolean accepted = workerHttpClient.submitRun(node, request);
            if (accepted) {
                log.info(
                        "任务已下发远程 Worker taskInstanceId={} nodeId={} taskType={}",
                        taskInstance.getId(),
                        node.getId(),
                        runSpec.getTaskType());
            }
            return accepted;
        } catch (Exception e) {
            log.error(
                    "远程下发任务失败 taskInstanceId={} nodeId={}",
                    taskInstance.getId(),
                    node.getId(),
                    e);
            return false;
        }
    }
}
