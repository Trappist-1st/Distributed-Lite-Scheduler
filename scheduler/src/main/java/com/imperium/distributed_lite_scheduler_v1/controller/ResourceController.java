package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceNodesRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceUsageRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResourceNodeRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReleaseResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceHeartbeatRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceUsage;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceService;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "资源节点", description = "Worker 注册、心跳、槽位预留与使用查询")
@RestController
@RequestMapping("/api/resource")
public class ResourceController {

    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;

    public ResourceController(ResourceService resourceService, ResourceSlotService resourceSlotService) {
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
    }

    @Operation(summary = "注册资源节点", description = "Worker 启动时调用，无需 JWT")
    @PostMapping("/register")
    public Result<ResourceNode> register(@RequestBody @Valid RegisterResourceNodeRequest request) {
        return resourceService.registerNode(request);
    }

    @Operation(summary = "节点心跳", description = "Worker 定期上报可用资源，无需 JWT")
    @PostMapping("/heartbeat")
    public Result<ResourceNode> heartbeat(@RequestBody @Valid ResourceHeartbeatRequest request) {
        return resourceService.heartbeat(request);
    }

    @Operation(summary = "查询资源节点列表", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @GetMapping("/nodes")
    public Result<List<ResourceNode>> listNodes(@ModelAttribute @Valid ListResourceNodesRequest request) {
        return resourceService.listNodes(request);
    }

    @Operation(
            summary = "超时节点下线",
            description = "将心跳超时的节点标记为 OFFLINE",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @PostMapping("/internal/offline-timeout")
    public Result<Integer> offlineTimeoutNodes(
            @RequestParam(defaultValue = "60") Integer heartbeatTimeoutSeconds) {
        return resourceService.offlineTimeoutNodes(heartbeatTimeoutSeconds);
    }

    @Operation(summary = "预留资源槽位", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @PostMapping("/reserve")
    public Result<ReserveResourceResponse> reserve(@RequestBody @Valid ReserveResourceRequest request) {
        return resourceSlotService.reserve(request);
    }

    @Operation(summary = "释放资源槽位", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @PostMapping("/release")
    public Result<Void> release(@RequestBody @Valid ReleaseResourceRequest request) {
        return resourceSlotService.release(request);
    }

    @Operation(summary = "查询资源使用流水", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @GetMapping("/usage")
    public Result<List<ResourceUsage>> listUsage(@ModelAttribute @Valid ListResourceUsageRequest request) {
        return resourceSlotService.listUsage(request);
    }
}
