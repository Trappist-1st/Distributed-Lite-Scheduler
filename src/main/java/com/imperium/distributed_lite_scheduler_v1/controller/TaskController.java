package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchCreateTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.service.TaskService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建任务
     * @param request
     * @return
     */
    @PostMapping("/create")
    public Result<Task> createTask(@RequestBody @Valid CreateTaskRequest request) {
        return taskService.createTask(request);
    }

    /**
     * 分页查询任务列表
     * @param request
     * @return
     */
    @GetMapping("/list")
    public Result<List<Task>> listTasks(@ModelAttribute @Valid ListTasksRequest request) {
        return taskService.listTasks(request);
    }

    /**
     * 获取任务详情
     * @param taskId
     * @return
     */
    @GetMapping("/{taskId}")
    public Result<Task> getTask(@PathVariable("taskId") Long taskId) {
        return taskService.getTask(taskId);
    }

    /**
     * 更新任务信息
     * @param taskId
     * @param request
     * @return
     */
    @PutMapping("/{taskId}")
    public Result<Task> updateTask(@PathVariable("taskId") Long taskId, @RequestBody @Valid UpdateTaskRequest request) {
        return taskService.updateTask(taskId, request);
    }

    /**
     * 删除任务
     * @param taskId
     * @return
     */
    @DeleteMapping("/{taskId}")
    public Result<Void> deleteTask(@PathVariable("taskId") Long taskId) {
        return taskService.deleteTask(taskId);
    }

    /**
     * 批量创建任务
     */
    @PostMapping("/batchCreate")
    public Result<List<Task>> batchCreateTasks(@RequestBody @Valid BatchCreateTasksRequest request) {
        return taskService.batchCreateTasks(request);
    }
}
