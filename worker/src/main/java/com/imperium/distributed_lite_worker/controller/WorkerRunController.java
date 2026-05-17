package com.imperium.distributed_lite_worker.controller;

import com.imperium.distributed_lite_worker.config.OpenApiConfig;
import com.imperium.distributed_lite_worker.dto.WorkerRunAcceptedResponse;
import com.imperium.distributed_lite_worker.dto.WorkerRunRequest;
import com.imperium.distributed_lite_worker.service.WorkerRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "任务执行", description = "调度中心 HTTP 下发与取消（需 X-Worker-Token，若已配置）")
@SecurityRequirement(name = OpenApiConfig.WORKER_TOKEN)
@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerRunController {

    private final WorkerRunService workerRunService;

    @Operation(
            summary = "提交异步执行任务",
            description = "立即返回 202，任务在线程池中执行；完成后回调调度中心内部状态 API")
    @PostMapping("/runs")
    public ResponseEntity<WorkerRunAcceptedResponse> submitRun(@RequestBody @Valid WorkerRunRequest request) {
        workerRunService.submitAsync(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new WorkerRunAcceptedResponse(request.getTaskInstanceId(), "accepted"));
    }

    @Operation(summary = "取消正在执行的任务")
    @PostMapping("/runs/{taskInstanceId}/cancel")
    public ResponseEntity<WorkerRunAcceptedResponse> cancelRun(
            @Parameter(description = "任务实例 ID") @PathVariable Long taskInstanceId) {
        boolean cancelled = workerRunService.cancel(taskInstanceId);
        String message = cancelled ? "cancelled" : "not_found_or_finished";
        return ResponseEntity.ok(new WorkerRunAcceptedResponse(taskInstanceId, message));
    }
}
