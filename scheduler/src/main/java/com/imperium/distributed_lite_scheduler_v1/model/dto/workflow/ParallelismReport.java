package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 并行度分析报告
 * 
 * 详细的并行度分析数据，用于工作流优化
 * 
 * @author system
 * @since 2024-01-01
 */
@Data
public class ParallelismReport {

    /** 与 WorkflowAnalysisServiceImpl 中阈值语义对齐 */
    private static final double LOW_AVG_PARALLELISM = 1.5d;
    private static final double LOW_PARALLEL_RATIO = 0.3d;
    private static final int LONG_CRITICAL_PATH_LAYERS = 8;
    
    /**
     * 平均并行度
     */
    private Double averageParallelism;
    
    /**
     * 并行化比率（可并行任务数 / 总任务数）
     */
    private Double parallelRatio;
    
    /**
     * 关键路径长度（最长执行路径的任务数）
     */
    private Integer criticalPathLength;
    
    /**
     * 每层的并行度分布
     * Key: 层索引, Value: 该层的并行度
     */
    private Map<Integer, Integer> layerParallelismDistribution;
    
    /**
     * 关键路径上的任务列表
     */
    private List<String> criticalPathTasks;
    
    /**
     * 瓶颈层索引（并行度最低的非首尾层）
     */
    private Integer bottleneckLayerIndex;
    
    /**
     * 优化建议
     */
    private List<String> optimizationSuggestions;
    
    /**
     * 生成优化建议
     * 
     * 算法：
     * 1. 检查平均并行度，如果过低给出建议
     * 2. 检查瓶颈层，给出改进建议
     * 3. 分析关键路径，建议优化耗时任务
     * 
     * @return 优化建议列表
     */
    public List<String> generateSuggestions() {
        // 1. 如果averageParallelism < 1.5，建议增加并行任务
        // 2. 如果存在瓶颈层，建议拆分任务
        // 3. 如果criticalPathLength过长，建议优化关键路径任务
        // 说明：本方法仅依据报告内已有字段生成简要建议；与 Service 层详细文案可并存
        List<String> suggestions = new ArrayList<>();
        if (averageParallelism != null && averageParallelism < LOW_AVG_PARALLELISM) {
            suggestions.add("平均并行度偏低（" + averageParallelism + "），可考虑减少串行依赖或拆分任务以增加同层并行节点");
        }
        if (bottleneckLayerIndex != null) {
            suggestions.add("存在瓶颈层（索引 " + bottleneckLayerIndex + "），建议评估该层任务拆分或依赖重组");
        }
        if (criticalPathLength != null && criticalPathLength >= LONG_CRITICAL_PATH_LAYERS) {
            suggestions.add("关键路径层数较多（" + criticalPathLength + "），在任务耗时相近时总时长受层数主导，建议优化关键路径上的任务或缩短单任务耗时");
        }
        if (parallelRatio != null && parallelRatio < LOW_PARALLEL_RATIO) {
            suggestions.add("可并行执行的任务占比较低（" + (parallelRatio * 100) + "%），可审视 DAG 以增加宽并行阶段");
        }
        return suggestions;
    }
}
