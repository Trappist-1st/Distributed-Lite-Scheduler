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

    Result<ResourceNode> registerNode(RegisterResourceNodeRequest request);

    Result<ResourceNode> heartbeat(ResourceHeartbeatRequest request);

    Result<List<ResourceNode>> listNodes(ListResourceNodesRequest request);

    Result<Integer> offlineTimeoutNodes(Integer heartbeatTimeoutSeconds);
}

