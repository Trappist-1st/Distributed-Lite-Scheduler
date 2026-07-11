package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskHeartbeatService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "内部-任务实例", description = "Worker 回调等内部状态流转接口（非 JWT，使用 X-Internal-Token）")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/api/internal/task-instances")
public class TaskInstanceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final TaskInstanceService taskInstanceService;
    private final TaskHeartbeatService taskHeartbeatService;
    private final String internalApiToken;

    public TaskInstanceController(
            TaskInstanceService taskInstanceService,
            TaskHeartbeatService taskHeartbeatService,
            @Value("${internal.api.token:}") String internalApiToken) {
        this.taskInstanceService = taskInstanceService;
        this.taskHeartbeatService = taskHeartbeatService;
        this.internalApiToken = internalApiToken;
    }

    @Operation(
            summary = "任务实例状态流转",
            description = "Worker 执行完成后回调，将 RUNNING 更新为 SUCCESS/FAILED/TIMEOUT 等")
    @PostMapping("/{id}/status")
    public Result<TaskInstance> transitionStatus(
            @Parameter(description = "任务实例 ID") @PathVariable("id") Long id,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody @Valid InternalTaskInstanceStatusTransitionRequest request) {
        if (!checkToken(internalToken)) {
            return tokenError();
        }
        return taskInstanceService.transitionStatus(id, request);
    }

    @Operation(
            summary = "任务实例心跳",
            description = "远程 Worker 在任务执行期间定期调用，刷新 last_heartbeat_at，防止被 Watchdog 误判为僵尸任务")
    @PostMapping("/{id}/heartbeat")
    public Result<Void> heartbeat(
            @Parameter(description = "任务实例 ID") @PathVariable("id") Long id,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        if (!checkToken(internalToken)) {
            return tokenError();
        }
        boolean alive = taskHeartbeatService.beat(id);
        if (!alive) {
            return Result.failure(ResultCode.NOT_FOUND, "任务已不在 RUNNING 状态，Worker 应停止执行");
        }
        return Result.success(null);
    }

    private boolean checkToken(String internalToken) {
        return StringUtils.hasText(internalApiToken) && internalApiToken.equals(internalToken);
    }

    private <T> Result<T> tokenError() {
        if (!StringUtils.hasText(internalApiToken)) {
            return Result.failure(ResultCode.SERVICE_UNAVAILABLE, "internal.api.token 未配置，内部接口不可用");
        }
        return Result.failure(ResultCode.FORBIDDEN, "内部接口鉴权失败");
    }
}
