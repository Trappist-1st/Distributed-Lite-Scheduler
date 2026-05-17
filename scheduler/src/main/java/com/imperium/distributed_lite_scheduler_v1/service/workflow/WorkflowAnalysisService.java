package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.ParallelismReport;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;

import java.util.List;

/**
 * 工作流分析服务接口
 * 
 * 提供工作流的性能分析和优化建议
 * 
 * @author system
 * @since 2024-01-01
 */
public interface WorkflowAnalysisService {
    
    /**
     * 分析工作流并行度
     * 
     * 分析内容：
     * 1. 平均并行度：总任务数 / 总层数
     * 2. 并行化比率：可并行任务数 / 总任务数
     *    - 可并行任务：位于并行度>1的层中的任务
     * 3. 关键路径长度：总层数（最长执行路径）
     * 4. 层并行度分布：每层的并行度统计
     * 5. 瓶颈识别：找出并行度最低的层
     * 
     * @param plan 执行计划
     * @return 并行度报告
     */
    ParallelismReport analyzeParallelism(WorkflowExecutionPlan plan);
    
    /**
     * 查找关键路径
     * 
     * 关键路径：从起点到终点的最长路径（决定总执行时间）
     * 
     * 算法（简化版，基于层数）：
     * 1. 对于当前实现，关键路径长度 = 总层数
     * 2. 遍历每一层，选择一个任务加入关键路径
     * 3. 优先选择有依赖的任务（TODO: 需要依赖图信息）
     * 
     * 注意：完整的关键路径分析需要考虑任务的实际执行时间
     * 当前版本假设所有任务执行时间相同
     * 
     * @param plan 执行计划
     * @return 关键路径上的任务名列表
     */
    List<String> findCriticalPath(WorkflowExecutionPlan plan);
    
    /**
     * 识别瓶颈层
     * 
     * 瓶颈定义：
     * - 并行度为1的层（串行执行）
     * - 或并行度明显低于平均并行度的层
     * - 排除首尾层（通常并行度较低是正常的）
     * 
     * 算法：
     * 1. 计算平均并行度
     * 2. 遍历中间层（排除第0层和最后一层）
     * 3. 找出并行度 < 平均并行度 * 0.5的层
     * 4. 返回这些层的索引
     * 
     * @param plan 执行计划
     * @return 瓶颈层索引列表
     */
    List<Integer> identifyBottlenecks(WorkflowExecutionPlan plan);
    
    /**
     * 生成优化建议
     * 
     * 建议类型：
     * 1. 低并行度建议：如果平均并行度 < 1.5，建议拆分任务或减少依赖
     * 2. 瓶颈层建议：对每个瓶颈层给出具体优化建议
     * 3. 关键路径建议：建议优化关键路径上的任务（减少执行时间）
     * 4. 资源利用建议：分析资源需求分布，给出资源配置建议
     * 
     * @param plan 执行计划
     * @param report 并行度报告
     * @return 优化建议列表
     */
    List<String> generateOptimizationSuggestions(WorkflowExecutionPlan plan, ParallelismReport report);
}
