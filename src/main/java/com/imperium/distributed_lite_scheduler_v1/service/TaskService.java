package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchCreateTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

import java.util.List;

public interface TaskService {
    Result<Task> createTask(CreateTaskRequest request);

    Result<List<Task>> listTasks(ListTasksRequest request);

    Result<Task> getTask(Long taskId);

    Result<Task> updateTask(Long taskId, UpdateTaskRequest request);

    Result<Void> deleteTask(Long taskId);

    Result<List<Task>> batchCreateTasks(BatchCreateTasksRequest request);
}
