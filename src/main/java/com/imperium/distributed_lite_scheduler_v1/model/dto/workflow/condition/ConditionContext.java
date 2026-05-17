package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * SpEL 求值上下文：任务结果、实例业务参数、系统只读变量。
 *
 * <p>实现 {@link com.imperium.distributed_lite_scheduler_v1.service.workflow.condition.ConditionEvaluator} 时，
 * 应将三者注册为变量（如 {@code tasks}、{@code context}、{@code system}），与设计稿 §4.3 一致。</p>
 */
@Data
public class ConditionContext {

    /**
     * key：DAG 节点名 {@code nodeName}；value：该节点已结束时的 {@link TaskResult}。
     */
    private Map<String, TaskResult> tasks = new HashMap<>();

    /**
     * 工作流实例级参数（如 batchId、userId）；来源可为实例 {@code context_json} 或创建请求。
     */
    private Map<String, Object> context = new HashMap<>();

    /**
     * 系统变量：如 workflowInstanceId、currentTime。
     */
    private Map<String, Object> system = new HashMap<>();
}
