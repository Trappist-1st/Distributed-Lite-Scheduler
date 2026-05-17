package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDAG;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;

/**
 * 工作流可视化服务接口
 * 
 * 提供工作流的图形化展示功能
 * 
 * @author system
 * @since 2024-01-01
 */
public interface WorkflowVisualizationService {
    
    /**
     * 生成Mermaid图（基于执行计划）
     * 
     * 生成格式：
     * graph TD
     *     A["A\nLayer 0"]
     *     B["B\nLayer 1"]
     *     C["C\nLayer 1"]
     *     D["D\nLayer 2"]
     *     A --> B
     *     A --> C
     *     B --> D
     *     C --> D
     * 
     * 算法：
     * 1. 构建节点定义部分
     *    - 遍历plan的所有层和任务
     *    - 生成节点：taskName["taskName\nLayer X"]
     * 2. 构建边定义部分（需要原始DAG）
     *    - 遍历DAG的dependencies
     *    - 生成边：from --> to
     * 
     * @param plan 执行计划
     * @param dag 原始DAG（用于获取依赖关系）
     * @return Mermaid图代码
     */
    String generateMermaidDiagram(WorkflowExecutionPlan plan, WorkflowDAG dag);
    
    /**
     * 生成分层可视化（ASCII艺术）
     * 
     * 示例输出：
     * Layer 0: [A]
     * Layer 1: [B, C]
     * Layer 2: [D]
     * 
     * @param plan 执行计划
     * @return ASCII格式的分层展示
     */
    String generateLayeredView(WorkflowExecutionPlan plan);
    
    /**
     * 生成执行时间线（甘特图风格）
     * 
     * 示例输出：
     * T0-T1:  [A]
     * T1-T2:  [B, C]
     * T2-T3:  [D]
     * 
     * 假设每个任务执行1个时间单位
     * 
     * @param plan 执行计划
     * @return 时间线字符串
     */
    String generateTimeline(WorkflowExecutionPlan plan);
}
