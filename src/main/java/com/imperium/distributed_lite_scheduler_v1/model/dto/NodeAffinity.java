package com.imperium.distributed_lite_scheduler_v1.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 节点亲和性/反亲和性约束模型（P3-4 可选增强）。
 */
@Data
public class NodeAffinity {
    // 硬性约束（必须满足）
    private List<String> requiredTags;
    private List<String> excludedTags;

    // 软性偏好（尽量满足）
    private List<String> preferredTags;
    private Long preferredNodeId;

    // 反亲和性（尽量避免）
    private List<Long> avoidNodeIds;
    private String avoidSameNodeAs;
}
