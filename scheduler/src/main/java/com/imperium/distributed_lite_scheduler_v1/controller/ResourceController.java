package com.imperium.distributed_lite_scheduler_v1.controller;

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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/resource")
public class ResourceController {

    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;

    public ResourceController(ResourceService resourceService, ResourceSlotService resourceSlotService) {
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
    }

    /**
     * Worker 节点注册。
     */
    @PostMapping("/register")
    public Result<ResourceNode> register(@RequestBody @Valid RegisterResourceNodeRequest request) {
        return resourceService.registerNode(request);
    }

    /**
     * 节点心跳上报。
     */
    @PostMapping("/heartbeat")
    public Result<ResourceNode> heartbeat(@RequestBody @Valid ResourceHeartbeatRequest request) {
        return resourceService.heartbeat(request);
    }

    /**
     * 节点状态分页查询。
     */
    @GetMapping("/nodes")
    public Result<List<ResourceNode>> listNodes(@ModelAttribute @Valid ListResourceNodesRequest request) {
        return resourceService.listNodes(request);
    }

    /**
     * 超时节点自动下线（可供内部定时任务触发）。
     */
    @PostMapping("/internal/offline-timeout")
    public Result<Integer> offlineTimeoutNodes(
            @RequestParam(defaultValue = "60") Integer heartbeatTimeoutSeconds) {
        return resourceService.offlineTimeoutNodes(heartbeatTimeoutSeconds);
    }

    /**
     * 为任务实例预留资源（P2-2）。
     */
    @PostMapping("/reserve")
    public Result<ReserveResourceResponse> reserve(@RequestBody @Valid ReserveResourceRequest request) {
        return resourceSlotService.reserve(request);
    }

    /**
     * 释放任务占用的槽位资源（P2-2）。
     */
    @PostMapping("/release")
    public Result<Void> release(@RequestBody @Valid ReleaseResourceRequest request) {
        return resourceSlotService.release(request);
    }

    /**
     * 分页查询资源使用流水（P2-2）。
     */
    @GetMapping("/usage")
    public Result<List<ResourceUsage>> listUsage(@ModelAttribute @Valid ListResourceUsageRequest request) {
        return resourceSlotService.listUsage(request);
    }
}

