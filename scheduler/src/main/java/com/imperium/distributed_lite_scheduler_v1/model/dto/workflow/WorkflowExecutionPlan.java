package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

import java.util.List;

/**
 * 工作流执行计划
 * 
 * 拓扑排序后的执行计划，包含分层的任务结构
 * 每一层的任务可以并行执行，层与层之间按顺序执行
 * 
 * @author system
 * @since 2024-01-01
 */
@Data
public class WorkflowExecutionPlan {
    
    /**
     * 工作流ID
     */
    private Long workflowId;
    
    /**
     * 工作流名称
     */
    private String workflowName;
    
    /**
     * 总层数
     */
    private Integer totalLayers;
    
    /**
     * 任务层列表（按执行顺序）
     */
    private List<TaskLayer> layers;

    /**
     * P4-4：DAG 依赖边快照（含可选 SpEL {@code condition}），与 {@link WorkflowDAG#getDependencies()} 同源。
     * <p>持久化在 {@link com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance#getExecutionPlan()} 中；
     * 由 {@link com.imperium.distributed_lite_scheduler_v1.service.workflow.impl.WorkflowExecutionServiceImpl} 构建计划时写入。</p>
     */
    private List<WorkflowDependency> dependencies;

    /**
     * 获取总任务数
     * 
     * 算法：
     * 1. 遍历所有层
     * 2. 累加每层的任务数量
     * 
     * @return 总任务数
     */
    public int getTotalTaskCount() {
        // 使用Stream API遍历layers
        // 对每个layer调用getTasks().size()
        // 累加求和
        if (layers == null || layers.isEmpty()) {
            return 0;
        }
        return layers.stream()
                .mapToInt(layer -> layer.getTasks() != null ? layer.getTasks().size() : 0)
                .sum();
    }
    
    /**
     * 获取最大并行度
     * 
     * 算法：
     * 1. 遍历所有层
     * 2. 找出并行度最大的层
     * 3. 返回该层的并行度
     * 
     * @return 最大并行度（同时执行的最多任务数）
     */
    public int getMaxParallelism() {
        // 使用Stream API遍历layers
        // 对每个layer调用getParallelism()
        // 找出最大值
        if (layers == null || layers.isEmpty()) {
            return 0;
        }
        return layers.stream()
                .mapToInt(layer -> layer.getParallelism() != null ? layer.getParallelism() : 0)
                .max()
                .orElse(0);
    }
    
    /**
     * 获取指定层
     * 
     * @param layerIndex 层索引
     * @return 任务层
     */
    public TaskLayer getLayer(int layerIndex) {
        // 检查layerIndex是否合法
        // 返回layers.get(layerIndex)
        if (layers == null || layerIndex < 0 || layerIndex >= layers.size()) {
            return null;
        }
        return layers.get(layerIndex);
    }
    
    /**
     * 计算理论最短执行时间（假设每个任务耗时相同）
     * 
     * @param avgTaskDurationMinutes 平均任务执行时间（分钟）
     * @return 理论最短执行时间（分钟）
     */
    public double estimateMinExecutionTime(double avgTaskDurationMinutes) {
        // 返回 totalLayers * avgTaskDurationMinutes
        int layersCount = totalLayers != null ? totalLayers : 0;
        return layersCount * avgTaskDurationMinutes;
    }
    
    /**
     * 计算串行执行时间（假设每个任务耗时相同）
     * 
     * @param avgTaskDurationMinutes 平均任务执行时间（分钟）
     * @return 串行执行时间（分钟）
     */
    public double estimateSerialExecutionTime(double avgTaskDurationMinutes) {
        // 返回 getTotalTaskCount() * avgTaskDurationMinutes
        return getTotalTaskCount() * avgTaskDurationMinutes;
    }
    
    /**
     * 计算加速比
     * 
     * @return 加速比（串行时间/并行时间）
     */
    public double calculateSpeedup() {
        // 返回 getTotalTaskCount() / (double) totalLayers
        int layersCount = totalLayers != null ? totalLayers : 0;
        if (layersCount == 0) {
            return 0;
        }
        return getTotalTaskCount() / (double) layersCount;
    }
}
