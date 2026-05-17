package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Step1：进程内执行器，将任务提交到本地线程池异步运行。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "task.executor.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessTaskDispatchService implements TaskDispatchService {

    private final TaskExecutorProperties properties;
    private final Executor taskExecutorThreadPool;
    private final LocalTaskRunner localTaskRunner;

    public InProcessTaskDispatchService(
            TaskExecutorProperties properties,
            @Qualifier("taskExecutorThreadPool") Executor taskExecutorThreadPool,
            LocalTaskRunner localTaskRunner) {
        this.properties = properties;
        this.taskExecutorThreadPool = taskExecutorThreadPool;
        this.localTaskRunner = localTaskRunner;
    }

    @Override
    public boolean dispatch(TaskInstance taskInstance, ResourceNode node) {
        if (!properties.isEnabled()) {
            log.warn("任务执行器未启用，拒绝提交 taskInstanceId={}", taskInstance.getId());
            return false;
        }
        Long taskInstanceId = taskInstance.getId();
        Long nodeId = node != null ? node.getId() : null;
        try {
            taskExecutorThreadPool.execute(() -> localTaskRunner.run(taskInstanceId, nodeId));
            log.info(
                    "任务已提交进程内执行器 taskInstanceId={} nodeId={}",
                    taskInstanceId,
                    nodeId);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("执行器线程池已满，拒绝任务 taskInstanceId={}", taskInstanceId, e);
            return false;
        }
    }
}
