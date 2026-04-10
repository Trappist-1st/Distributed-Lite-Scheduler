package com.imperium.distributed_lite_scheduler_v1.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

public interface TaskInstanceService extends IService<TaskInstance> {

    Result<TaskInstance> transitionStatus(Long taskInstanceId, InternalTaskInstanceStatusTransitionRequest request);
}

