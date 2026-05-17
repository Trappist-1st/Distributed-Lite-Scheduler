package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 工作流依赖关系
 * 
 * 定义DAG中任务节点之间的依赖关系（有向边）：from -> to
 * 表示to节点依赖于from节点，即from完成后才能执行to
 * 
 * 依赖关系说明：
 * - from/to 使用的是 WorkflowTask.nodeName（节点名称），不是 taskId
 * - 一个节点可以依赖多个上游节点（多个from指向同一个to）
 * - 一个节点可以被多个下游节点依赖（一个from指向多个to）
 * - 不允许出现循环依赖，创建时需要进行DAG验证
 *
 */
@Data
public class WorkflowDependency {
    
    /**
     * 上游节点名称（依赖源）
     * 对应 WorkflowTask.nodeName
     */
    private String from;
    
    /**
     * 下游节点名称（依赖目标）
     * 对应 WorkflowTask.nodeName
     */
    private String to;
    
    /**
     * 执行条件（SpEL表达式，可选）
     * 为null或空字符串表示无条件执行
     * 
     * 支持的变量：
     * - tasks.{nodeName}.status: 任务执行状态
     * - tasks.{nodeName}.exitCode: 任务退出码
     * - tasks.{nodeName}.output: 任务输出结果（如果有）
     * 
     * 例如：
     * - ${tasks.data_validation.status == 'SUCCESS'}
     * - ${tasks.data_validation.exitCode == 0}
     * - ${tasks.data_validation.output.score >= 90}
     */
    private String condition;
}
