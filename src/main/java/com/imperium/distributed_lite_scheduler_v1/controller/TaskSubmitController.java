package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-submit")
public class TaskSubmitController {
    /**
     * 各个任务提交的接口
     */

    private final TaskSubmitService taskSubmitService;

    public TaskSubmitController(TaskSubmitService taskSubmitService) {
        this.taskSubmitService = taskSubmitService;
    }
    /**
     * 提交单个新任务
     */
    @PostMapping("/submit")
    public Result<TaskSubmitResponse> submitTask(@RequestBody @Valid TaskSubmitRequest request){
        return taskSubmitService.submitTask(request);
    }

    /**
     * 批量提交新任务
     */
    @PostMapping("/submit-batch")
    public Result<BatchTaskSubmitResponse> submitBatch(@RequestBody @Valid BatchTaskSubmitRequest request){
        return taskSubmitService.submitBatch(request);
    }
}
