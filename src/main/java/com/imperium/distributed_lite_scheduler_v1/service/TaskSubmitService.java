package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

public interface TaskSubmitService {

    Result<TaskSubmitResponse> submitTask(TaskSubmitRequest request);

    Result<BatchTaskSubmitResponse> submitBatch(BatchTaskSubmitRequest request);
}
