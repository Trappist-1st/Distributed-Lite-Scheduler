package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceNodesRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResourceNodeRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceHeartbeatRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

import java.util.List;

/**
 * 资源节点管理服务接口（P2-1）。
 */
public interface ResourceService {

    // 注册资源节点并返回落库后的节点信息。
    Result<ResourceNode> registerNode(RegisterResourceNodeRequest request);

    // 上报节点心跳并刷新节点存活状态。
    Result<ResourceNode> heartbeat(ResourceHeartbeatRequest request);

    // 按条件查询资源节点列表。
    Result<List<ResourceNode>> listNodes(ListResourceNodesRequest request);

    // 将超时未心跳的节点批量下线并返回下线数量。
    Result<Integer> offlineTimeoutNodes(Integer heartbeatTimeoutSeconds);
}

