package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;

/**
 * 调度器在任务进入 RUNNING 后，将任务提交给执行器。
 */
public interface TaskDispatchService {

    /**
     * 异步提交任务到执行器。
     *
     * @return true 表示已接受执行；false 表示未提交（调度器应回滚）
     */
    boolean dispatch(TaskInstance taskInstance, ResourceNode node);
}
