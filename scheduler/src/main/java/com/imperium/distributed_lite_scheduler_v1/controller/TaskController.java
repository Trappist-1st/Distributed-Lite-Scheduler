package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchCreateTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.service.TaskService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "任务定义", description = "调度任务元数据 CRUD（非任务实例）")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "创建任务定义")
    @PostMapping("/create")
    public Result<Task> createTask(@RequestBody @Valid CreateTaskRequest request) {
        return taskService.createTask(request);
    }

    @Operation(summary = "查询任务列表")
    @GetMapping("/list")
    public Result<List<Task>> listTasks(@ModelAttribute @Valid ListTasksRequest request) {
        return taskService.listTasks(request);
    }

    @Operation(summary = "查询任务详情")
    @GetMapping("/{taskId}")
    public Result<Task> getTask(@Parameter(description = "任务定义 ID") @PathVariable("taskId") Long taskId) {
        return taskService.getTask(taskId);
    }

    @Operation(summary = "更新任务定义")
    @PutMapping("/{taskId}")
    public Result<Task> updateTask(
            @PathVariable("taskId") Long taskId,
            @RequestBody @Valid UpdateTaskRequest request) {
        return taskService.updateTask(taskId, request);
    }

    @Operation(summary = "删除任务定义")
    @DeleteMapping("/{taskId}")
    public Result<Void> deleteTask(@PathVariable("taskId") Long taskId) {
        return taskService.deleteTask(taskId);
    }

    @Operation(summary = "批量创建任务定义")
    @PostMapping("/batchCreate")
    public Result<List<Task>> batchCreateTasks(@RequestBody @Valid BatchCreateTasksRequest request) {
        return taskService.batchCreateTasks(request);
    }
}
