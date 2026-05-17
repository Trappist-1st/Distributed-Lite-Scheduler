package com.imperium.distributed_lite_worker.controller;

import com.imperium.distributed_lite_worker.dto.WorkerRunAcceptedResponse;
import com.imperium.distributed_lite_worker.dto.WorkerRunRequest;
import com.imperium.distributed_lite_worker.service.WorkerRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerRunController {

    private final WorkerRunService workerRunService;

    @PostMapping("/runs")
    public ResponseEntity<WorkerRunAcceptedResponse> submitRun(@RequestBody @Valid WorkerRunRequest request) {
        workerRunService.submitAsync(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new WorkerRunAcceptedResponse(request.getTaskInstanceId(), "accepted"));
    }

    @PostMapping("/runs/{taskInstanceId}/cancel")
    public ResponseEntity<WorkerRunAcceptedResponse> cancelRun(@PathVariable Long taskInstanceId) {
        boolean cancelled = workerRunService.cancel(taskInstanceId);
        String message = cancelled ? "cancelled" : "not_found_or_finished";
        return ResponseEntity.ok(new WorkerRunAcceptedResponse(taskInstanceId, message));
    }
}
