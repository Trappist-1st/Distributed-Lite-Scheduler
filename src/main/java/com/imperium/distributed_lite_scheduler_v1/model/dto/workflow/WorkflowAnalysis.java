package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 工作流分析结果
 * 
 * 对工作流执行计划的统计分析数据
 * 
 * @author system
 * @since 2024-01-01
 */
@Data
public class WorkflowAnalysis {
    
    /**
     * 总任务数
     */
    private Integer totalTasks;
    
    /**
     * 总层数
     */
    private Integer totalLayers;
    
    /**
     * 最大并行度
     */
    private Integer maxParallelism;
    
    /**
     * 理论最短执行时间（分钟）
     * 假设每个任务执行1分钟
     */
    private Integer minExecutionMinutes;
    
    /**
     * 串行执行时间（分钟）
     * 假设每个任务执行1分钟
     */
    private Integer serialExecutionMinutes;
    
    /**
     * 加速比（串行时间/并行时间）
     */
    private Double speedup;
    
    /**
     * 平均并行度
     */
    private Double averageParallelism;
    
    /**
     * 并行化比率（可并行任务占比）
     */
    private Double parallelizationRatio;
    
    /**
     * 计算时间节省百分比
     * 
     * @return 节省的时间百分比
     */
    public double getTimeSavingPercentage() {
        // 计算公式: (serialExecutionMinutes - minExecutionMinutes) / serialExecutionMinutes * 100
        // 在「每任务 1 分钟」假设下，表示相对完全串行执行可节省的时间比例
        if (serialExecutionMinutes == null || serialExecutionMinutes <= 0) {
            return 0.0d;
        }
        int serial = serialExecutionMinutes;
        int min = minExecutionMinutes != null ? minExecutionMinutes : 0;
        return ((double) serial - (double) min) / (double) serial * 100.0d;
    }
}
