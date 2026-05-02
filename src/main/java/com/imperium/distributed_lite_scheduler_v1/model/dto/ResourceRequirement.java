package com.imperium.distributed_lite_scheduler_v1.model.dto;

import com.imperium.distributed_lite_scheduler_v1.constant.NodeType;
import lombok.Data;

import java.util.List;

/**
 * 资源需求模型（供调度器解析 taskInstance.resourceRequirement）。
 */
@Data
public class ResourceRequirement {
    // 基础资源
    private Double cpu;
    private Long memoryMb;
    private Integer gpu;

    // 高级需求（可选）
    private NodeType nodeType;
    private List<String> tags;
    private String gpuModel;
    private Integer minCpuCores;
    private Long minMemoryMb;

    // 亲和性（可选）
    private Long preferredNodeId;
    private List<Long> excludeNodeIds;
}
