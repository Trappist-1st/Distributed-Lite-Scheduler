package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "任务提交", description = "创建任务实例并进入调度队列（削峰）")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/task-submit")
public class TaskSubmitController {

    private final TaskSubmitService taskSubmitService;

    public TaskSubmitController(TaskSubmitService taskSubmitService) {
        this.taskSubmitService = taskSubmitService;
    }

    @Operation(summary = "提交单个任务实例", description = "创建 PENDING 状态任务实例，由调度器后续分配资源并执行")
    @PostMapping("/submit")
    public Result<TaskSubmitResponse> submitTask(@RequestBody @Valid TaskSubmitRequest request) {
        return taskSubmitService.submitTask(request);
    }

    @Operation(summary = "批量提交任务实例")
    @PostMapping("/submit-batch")
    public Result<BatchTaskSubmitResponse> submitBatch(@RequestBody @Valid BatchTaskSubmitRequest request) {
        return taskSubmitService.submitBatch(request);
    }
}
