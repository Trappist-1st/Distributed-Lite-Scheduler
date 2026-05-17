package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskExecutionNode;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskLayer;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDAG;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDependency;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowVisualizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 工作流可视化服务实现
 *
 * @author system
 * @since 2024-01-01
 */
@Service
@Slf4j
public class WorkflowVisualizationServiceImpl implements WorkflowVisualizationService {

    private static final int SEP_WIDTH = 50;
    private static final String SEP_LINE = "=".repeat(SEP_WIDTH);

    @Override
    public String generateMermaidDiagram(WorkflowExecutionPlan plan, WorkflowDAG dag) {
        // 1. 创建StringBuilder
        //    - StringBuilder sb = new StringBuilder()
        //    - sb.append("graph TD\n")
        if (plan == null || plan.getLayers() == null || plan.getLayers().isEmpty()) {
            return "graph TD\n    _empty[\"(无可视化数据)\"]\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        // 2. 生成节点定义
        //    - 遍历plan.getLayers()
        //    - 对每一层遍历tasks
        //    - 生成格式：
        //      sb.append("    ").append(node.getTaskName())
        //        .append("[\"").append(node.getTaskName())
        //        .append("\\nLayer ").append(layer.getLayerIndex())
        //        .append("\"]\n")
        // 说明：Mermaid 节点 ID 需尽量为 [a-zA-Z0-9_]，故对 id 做安全化，展示文案仍用原始 taskName
        for (TaskLayer layer : plan.getLayers()) {
            if (layer == null || layer.getTasks() == null) {
                continue;
            }
            Integer layerIndex = layer.getLayerIndex();
            for (TaskExecutionNode node : layer.getTasks()) {
                if (node == null || node.getTaskName() == null || node.getTaskName().isBlank()) {
                    continue;
                }
                String id = mermaidSafeId(node.getTaskName());
                String nameEscaped = escapeForMermaidDoubleQuotedLabel(node.getTaskName());
                int li = layerIndex != null ? layerIndex : 0;
                sb.append("    ")
                        .append(id)
                        .append("[\"")
                        .append(nameEscaped)
                        .append("\\nLayer ")
                        .append(li)
                        .append("\"]\n");
            }
        }

        // 3. 生成边定义
        //    - 遍历dag.getDependencies()
        //    - 生成格式：
        //      sb.append("    ").append(dep.getFrom())
        //        .append(" --> ").append(dep.getTo())
        //        .append("\n")
        if (dag != null && dag.getDependencies() != null) {
            for (WorkflowDependency dep : dag.getDependencies()) {
                if (dep == null || dep.getFrom() == null || dep.getTo() == null) {
                    continue;
                }
                sb.append("    ")
                        .append(mermaidSafeId(dep.getFrom()))
                        .append(" --> ")
                        .append(mermaidSafeId(dep.getTo()))
                        .append("\n");
            }
        }

        // 4. return sb.toString()
        return sb.toString();
    }

    @Override
    public String generateLayeredView(WorkflowExecutionPlan plan) {
        // 1. StringBuilder sb = new StringBuilder()
        // 2. sb.append("工作流执行计划分层视图\n")
        // 3. sb.append("=" * 50).append("\n")
        if (plan == null) {
            return "(无执行计划)\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("工作流执行计划分层视图\n");
        sb.append(SEP_LINE).append("\n");

        // 4. 遍历plan.getLayers()
        List<TaskLayer> layers = plan.getLayers() != null ? plan.getLayers() : Collections.emptyList();
        for (TaskLayer layer : layers) {
            if (layer == null) {
                continue;
            }
            // 5. 对每一层：
            //    - sb.append("Layer ").append(layer.getLayerIndex()).append(": ")
            //    - 获取任务名列表：
            //      List<String> taskNames = layer.getTasks().stream()
            //          .map(TaskExecutionNode::getTaskName)
            //          .collect(Collectors.toList())
            //    - sb.append(taskNames).append(" (并行度: ").append(layer.getParallelism()).append(")\n")
            int idx = layer.getLayerIndex() != null ? layer.getLayerIndex() : 0;
            sb.append("Layer ").append(idx).append(": ");
            List<String> taskNames = new ArrayList<>();
            if (layer.getTasks() != null) {
                taskNames =
                        layer.getTasks().stream()
                                .filter(n -> n != null && n.getTaskName() != null && !n.getTaskName().isBlank())
                                .map(TaskExecutionNode::getTaskName)
                                .collect(Collectors.toList());
            }
            sb.append("[").append(String.join(", ", taskNames)).append("]");
            Integer par = layer.getParallelism();
            sb.append(" (并行度: ").append(par != null ? par : taskNames.size()).append(")\n");
        }

        // 6. sb.append("=" * 50).append("\n")
        sb.append(SEP_LINE).append("\n");
        // 7. sb.append("总任务数: ").append(plan.getTotalTaskCount()).append("\n")
        // 8. sb.append("总层数: ").append(plan.getTotalLayers()).append("\n")
        // 9. sb.append("最大并行度: ").append(plan.getMaxParallelism()).append("\n")
        // 10. return sb.toString()
        sb.append("总任务数: ").append(plan.getTotalTaskCount()).append("\n");
        sb.append("总层数: ").append(plan.getTotalLayers() != null ? plan.getTotalLayers() : layers.size())
                .append("\n");
        sb.append("最大并行度: ").append(plan.getMaxParallelism()).append("\n");
        return sb.toString();
    }

    @Override
    public String generateTimeline(WorkflowExecutionPlan plan) {
        // 1. StringBuilder sb = new StringBuilder()
        // 2. sb.append("工作流执行时间线（假设每任务1单位时间）\n")
        // 3. sb.append("=" * 50).append("\n")
        if (plan == null) {
            return "(无执行计划)\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("工作流执行时间线（假设每任务1单位时间）\n");
        sb.append(SEP_LINE).append("\n");

        // 4. 遍历plan.getLayers()
        List<TaskLayer> layers = plan.getLayers() != null ? plan.getLayers() : Collections.emptyList();
        for (TaskLayer layer : layers) {
            if (layer == null) {
                continue;
            }
            // 5. 对每一层：
            //    - int layerIdx = layer.getLayerIndex()
            //    - sb.append("T").append(layerIdx).append("-T").append(layerIdx + 1).append(": ")
            //    - 获取任务名列表并格式化
            //    - sb.append(taskNames).append("\n")
            int layerIdx = layer.getLayerIndex() != null ? layer.getLayerIndex() : 0;
            sb.append("T").append(layerIdx).append("-T").append(layerIdx + 1).append(":  ");
            List<String> taskNames = new ArrayList<>();
            if (layer.getTasks() != null) {
                taskNames =
                        layer.getTasks().stream()
                                .filter(n -> n != null && n.getTaskName() != null && !n.getTaskName().isBlank())
                                .map(TaskExecutionNode::getTaskName)
                                .collect(Collectors.toList());
            }
            sb.append("[").append(String.join(", ", taskNames)).append("]\n");
        }

        // 6. sb.append("=" * 50).append("\n")
        sb.append(SEP_LINE).append("\n");
        // 7. sb.append("总执行时间: ").append(plan.getTotalLayers()).append(" 单位\n")
        Integer totalLayers = plan.getTotalLayers();
        sb.append("总执行时间: ")
                .append(totalLayers != null ? totalLayers : layers.size())
                .append(" 单位\n");
        // 8. double speedup = plan.calculateSpeedup()
        // 9. sb.append("加速比: ").append(String.format("%.2f", speedup)).append("x\n")
        double speedup = plan.calculateSpeedup();
        sb.append("加速比: ").append(String.format(Locale.ROOT, "%.2f", speedup)).append("x\n");
        // 10. return sb.toString()
        return sb.toString();
    }

    /**
     * Mermaid 节点 ID：仅保留安全字符，避免依赖名中的 -、. 等破坏语法。
     */
    private static String mermaidSafeId(String name) {
        if (name == null || name.isBlank()) {
            return "n_empty";
        }
        String base = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (base.isEmpty()) {
            return "n_" + Math.abs(name.hashCode());
        }
        if (Character.isDigit(base.charAt(0))) {
            return "n_" + base;
        }
        return base;
    }

    /** Mermaid 双引号节点文案内：转义 \ 与 " */
    private static String escapeForMermaidDoubleQuotedLabel(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
