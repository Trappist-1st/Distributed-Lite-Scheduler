package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/task-instances")
public class TaskInstanceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final TaskInstanceService taskInstanceService;
    private final String internalApiToken;

    public TaskInstanceController(
            TaskInstanceService taskInstanceService,
            @Value("${internal.api.token:}") String internalApiToken) {
        this.taskInstanceService = taskInstanceService;
        this.internalApiToken = internalApiToken;
    }

    @PostMapping("/{id}/status")
    public Result<TaskInstance> transitionStatus(
            @PathVariable("id") Long id,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody @Valid InternalTaskInstanceStatusTransitionRequest request) {
        if (!StringUtils.hasText(internalApiToken)) {
            return Result.failure(ResultCode.SERVICE_UNAVAILABLE, "internal.api.token 未配置，内部接口不可用");
        }
        if (!internalApiToken.equals(internalToken)) {
            return Result.failure(ResultCode.FORBIDDEN, "内部接口鉴权失败");
        }
        return taskInstanceService.transitionStatus(id, request);
    }
}
