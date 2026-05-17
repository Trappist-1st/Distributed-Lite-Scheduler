package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.ParallelismReport;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskExecutionNode;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskLayer;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流分析服务实现
 *
 * @author system
 * @since 2024-01-01
 */
//TODO：更加完善的实现：考虑任务的实际依赖关系，使用动态规划计算最长路径，考虑任务的预估执行时间，分析资源需求分布，给出资源配置建议
@Service
@Slf4j
public class WorkflowAnalysisServiceImpl implements WorkflowAnalysisService {

    private static final double LOW_AVG_PARALLELISM = 1.5d;
    private static final double LOW_PARALLEL_RATIO = 0.3d;
    private static final double BOTTLENECK_THRESHOLD_FACTOR = 0.5d;
    private static final double CRITICAL_PATH_DEPTH_RATIO = 0.7d;

    @Override
    public ParallelismReport analyzeParallelism(WorkflowExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("执行计划不能为空");
        }

        // 1. 创建ParallelismReport对象
        ParallelismReport report = new ParallelismReport();

        int totalTasks = plan.getTotalTaskCount();
        Integer totalLayersBoxed = plan.getTotalLayers();
        int totalLayers = totalLayersBoxed != null ? totalLayersBoxed : 0;
        List<TaskLayer> layers = plan.getLayers() != null ? plan.getLayers() : Collections.emptyList();
        if (totalLayers <= 0 && !layers.isEmpty()) {
            totalLayers = layers.size();
        }

        // 2. 计算平均并行度
        double averageParallelism =
                totalLayers > 0 ? (double) totalTasks / (double) totalLayers : 0.0d;
        report.setAverageParallelism(averageParallelism);

        // 3. 计算并行化比率
        int parallelTasks = 0;
        for (TaskLayer layer : layers) {
            int p = layer.getParallelism() != null ? layer.getParallelism() : 0;
            if (p > 1) {
                int count = layer.getTasks() != null ? layer.getTasks().size() : p;
                parallelTasks += count;
            }
        }
        double parallelRatio = totalTasks > 0 ? (double) parallelTasks / (double) totalTasks : 0.0d;
        report.setParallelRatio(parallelRatio);

        // 4. 设置关键路径长度
        //    - report.setCriticalPathLength(totalLayers)
        // 说明：当前简化模型下，分层拓扑的「关键路径长度」取总层数（与接口文档一致）
        report.setCriticalPathLength(totalLayers);

        // 5. 构建层并行度分布
        Map<Integer, Integer> distribution = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            TaskLayer layer = layers.get(i);
            int idx = layer.getLayerIndex() != null ? layer.getLayerIndex() : i;
            int par = layer.getParallelism() != null ? layer.getParallelism() : 0;
            distribution.put(idx, par);
        }
        report.setLayerParallelismDistribution(distribution);

        // 6. 识别瓶颈层
        List<Integer> bottlenecks = identifyBottlenecks(plan);
        if (!bottlenecks.isEmpty()) {
            report.setBottleneckLayerIndex(bottlenecks.get(0));
        }

        // 7. 查找关键路径
        List<String> criticalPath = findCriticalPath(plan);
        report.setCriticalPathTasks(criticalPath);

        // 8. 生成优化建议
        List<String> suggestions = generateOptimizationSuggestions(plan, report);
        report.setOptimizationSuggestions(suggestions);

        // 9. 记录日志
        log.info(
                "并行度分析完成 totalTasks={} totalLayers={} avgParallelism={} maxParallelism={}",
                totalTasks,
                totalLayers,
                averageParallelism,
                plan.getMaxParallelism());

        // 10. return report
        return report;
    }

    @Override
    public List<String> findCriticalPath(WorkflowExecutionPlan plan) {
        // 当前版本：简单地从每一层选择第一个任务（简化版关键路径）
        //
        // 1. List<String> criticalPath = new ArrayList<>()
        List<String> criticalPath = new ArrayList<>();
        if (plan == null || plan.getLayers() == null) {
            return criticalPath;
        }
        // 2. 遍历plan.getLayers()
        for (TaskLayer layer : plan.getLayers()) {
            // 3. 对每一层，取第一个任务：
            List<TaskExecutionNode> tasks = layer.getTasks();
            if (tasks != null && !tasks.isEmpty()) {
                TaskExecutionNode first = tasks.get(0);
                if (first.getTaskName() != null) {
                    criticalPath.add(first.getTaskName());
                }
            }
        }
        
        // 注意：完整实现需要：
        // - 考虑任务的实际依赖关系
        // - 考虑任务的预估执行时间
        // - 使用动态规划计算最长路径
        return criticalPath;
    }

    @Override
    public List<Integer> identifyBottlenecks(WorkflowExecutionPlan plan) {
        // 1. List<Integer> bottlenecks = new ArrayList<>()
        List<Integer> bottlenecks = new ArrayList<>();
        if (plan == null || plan.getLayers() == null || plan.getLayers().isEmpty()) {
            return bottlenecks;
        }
        List<TaskLayer> layers = plan.getLayers();

        // 2. 计算平均并行度
        //    - double avgParallelism = (double) plan.getTotalTaskCount() / plan.getTotalLayers()
        int totalTasks = plan.getTotalTaskCount();
        int totalLayers = plan.getTotalLayers() != null ? plan.getTotalLayers() : layers.size();
        if (totalLayers <= 0) {
            return bottlenecks;
        }
        double avgParallelism = (double) totalTasks / (double) totalLayers;

        // 3. 定义瓶颈阈值
        //    - double threshold = avgParallelism * 0.5
        double threshold = avgParallelism * BOTTLENECK_THRESHOLD_FACTOR;

        // 4. 遍历中间层（排除第0层和最后一层）
        //    - List<TaskLayer> layers = plan.getLayers()
        //    - for (int i = 1; i < layers.size() - 1; i++) {
        //        TaskLayer layer = layers.get(i)
        //        if (layer.getParallelism() < threshold) {
        //            bottlenecks.add(layer.getLayerIndex())
        //        }
        //    }
        for (int i = 1; i < layers.size() - 1; i++) {
            TaskLayer layer = layers.get(i);
            int p = layer.getParallelism() != null ? layer.getParallelism() : 0;
            if (p < threshold) {
                int layerIndex = layer.getLayerIndex() != null ? layer.getLayerIndex() : i;
                bottlenecks.add(layerIndex);
            }
        }

        // 5. 按并行度从小到大排序
        //    - bottlenecks.sort(Comparator.comparing(idx -> plan.getLayer(idx).getParallelism()))
        bottlenecks.sort(
                Comparator.comparingInt(
                        idx -> {
                            TaskLayer l = plan.getLayer(idx);
                            if (l == null || l.getParallelism() == null) {
                                return Integer.MAX_VALUE;
                            }
                            return l.getParallelism();
                        }));

        // 6. return bottlenecks
        return bottlenecks;
    }

    @Override
    public List<String> generateOptimizationSuggestions(WorkflowExecutionPlan plan, ParallelismReport report) {
        // 1. List<String> suggestions = new ArrayList<>()
        List<String> suggestions = new ArrayList<>();
        if (plan == null || report == null) {
            return suggestions;
        }

        Double avg = report.getAverageParallelism();
        Integer bottleneckIdx = report.getBottleneckLayerIndex();
        Integer criticalLen = report.getCriticalPathLength();
        Double parallelRatio = report.getParallelRatio();
        int totalTasks = plan.getTotalTaskCount();

        // 2. 检查平均并行度
        //    - if (report.getAverageParallelism() < 1.5) {
        //        suggestions.add("工作流并行度较低（平均" + report.getAverageParallelism() + "），建议：")
        //        suggestions.add("  - 检查任务间的依赖关系，减少不必要的串行依赖")
        //        suggestions.add("  - 考虑将大任务拆分为多个可并行的小任务")
        //    }
        if (avg != null && avg < LOW_AVG_PARALLELISM) {
            suggestions.add("工作流并行度较低（平均" + avg + "），建议：");
            suggestions.add("  - 检查任务间的依赖关系，减少不必要的串行依赖");
            suggestions.add("  - 考虑将大任务拆分为多个可并行的小任务");
        }

        // 3. 检查瓶颈层
        //    - if (report.getBottleneckLayerIndex() != null) {
        //        int bottleneckIdx = report.getBottleneckLayerIndex()
        //        TaskLayer bottleneckLayer = plan.getLayer(bottleneckIdx)
        //        suggestions.add("检测到瓶颈层 Layer " + bottleneckIdx + "，并行度仅为" + bottleneckLayer.getParallelism())
        //        suggestions.add("  - 分析该层的任务，考虑拆分或重组")
        //    }
        if (bottleneckIdx != null) {
            TaskLayer bottleneckLayer = plan.getLayer(bottleneckIdx);
            int par =
                    bottleneckLayer != null && bottleneckLayer.getParallelism() != null
                            ? bottleneckLayer.getParallelism()
                            : 0;
            suggestions.add("检测到瓶颈层 Layer " + bottleneckIdx + "，并行度仅为" + par);
            suggestions.add("  - 分析该层的任务，考虑拆分或重组");
        }

        // 4. 检查关键路径
        //    - if (report.getCriticalPathLength() > plan.getTotalTaskCount() * 0.7) {
        //        suggestions.add("关键路径较长（" + report.getCriticalPathLength() + "层），建议优化以下方面：")
        //        suggestions.add("  - 减少关键路径上的任务数量")
        //        suggestions.add("  - 优化关键路径任务的执行效率")
        //    }
        if (criticalLen != null && totalTasks > 0 && criticalLen > totalTasks * CRITICAL_PATH_DEPTH_RATIO) {
            suggestions.add("关键路径较长（" + criticalLen + "层），建议优化以下方面：");
            suggestions.add("  - 减少关键路径上的任务数量");
            suggestions.add("  - 优化关键路径任务的执行效率");
        }

        // 5. 检查并行化比率
        //    - if (report.getParallelRatio() < 0.3) {
        //        suggestions.add("仅" + (report.getParallelRatio() * 100) + "%的任务可并行执行，并行化程度低")
        //        suggestions.add("  - 重新设计工作流结构，增加并行执行的机会")
        //    }
        if (parallelRatio != null && parallelRatio < LOW_PARALLEL_RATIO) {
            suggestions.add("仅" + (parallelRatio * 100) + "%的任务可并行执行，并行化程度低");
            suggestions.add("  - 重新设计工作流结构，增加并行执行的机会");
        }

        // 6. 如果没有明显问题
        //    - if (suggestions.isEmpty()) {
        //        suggestions.add("工作流结构良好，并行度合理")
        //    }
        if (suggestions.isEmpty()) {
            suggestions.add("工作流结构良好，并行度合理");
        }

        // 7. return suggestions
        return suggestions;
    }
}
