package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;
import java.util.List;

/**
 * 工作流DAG（有向无环图）定义
 * 
 * 表示一个完整的工作流执行图，包含：
 * 1. 节点列表（tasks）：每个节点引用一个 Task 实体
 * 2. 边列表（dependencies）：定义节点之间的执行依赖关系
 * 
 * DAG 约束：
 * - 节点名称（nodeName）在同一个 DAG 中必须唯一
 * - 依赖关系不能形成环（必须是无环图）
 * - 依赖关系引用的节点必须存在
 * - 引用的 Task 实体（taskId）必须在 task 表中存在
 * 
 * 执行规则：
 * - 没有上游依赖的节点可以并行执行
 * - 有上游依赖的节点必须等待所有上游节点完成后才能执行
 * - 如果依赖关系设置了 condition，则需要条件满足才执行
 *
 */
@Data
public class WorkflowDAG {
    
    /**
     * DAG版本
     * 用于标识 DAG 定义的版本，便于后续扩展和兼容性处理
     */
    private String version;
    
    /**
     * 任务节点列表
     * 每个 WorkflowTask 代表 DAG 中的一个节点，引用已定义的 Task 实体
     */
    private List<WorkflowTask> tasks;
    
    /**
     * 依赖关系列表（有向边）
     * 每个 WorkflowDependency 代表 from -> to 的依赖关系
     * 表示 to 节点依赖于 from 节点（from 完成后才能执行 to）
     */
    private List<WorkflowDependency> dependencies;
}
