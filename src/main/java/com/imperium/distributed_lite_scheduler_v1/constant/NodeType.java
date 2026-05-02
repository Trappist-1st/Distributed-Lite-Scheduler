package com.imperium.distributed_lite_scheduler_v1.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 资源节点类型枚举（P3-4）。
 */
@Getter
@RequiredArgsConstructor
public enum NodeType {
    CPU("CPU节点", "适合计算密集型任务"),
    GPU("GPU节点", "适合深度学习与图像处理任务"),
    MIXED("混合节点", "同时具备CPU和GPU能力");

    private final String name;
    private final String description;
}
