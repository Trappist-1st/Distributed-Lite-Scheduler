package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 条件求值用：单个工作流任务节点（nodeName）的运行结果摘要。
 *
 * <p>对应 P4-4 设计稿中的 {@code TaskResult}，供 SpEL 中 {@code #tasks['nodeName']} 形态访问。</p>
 * <p>{@link #output} 通常由 {@code workflow_task_instance.output} 的 JSON 解析得到。</p>
 */
@Data
public class TaskResult {

    /**
     * 任务状态存库值，建议与 {@link com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus} 的 code 一致（如 SUCCESS）。
     */
    private String status;

    private Integer exitCode;

    /**
     * 结构化输出；无输出时使用空 Map 避免 SpEL 空指针。
     */
    private Map<String, Object> output = new HashMap<>();

    /**
     * 执行时长（秒），来自工作流任务实例或调度侧统计。
     */
    private Long durationSeconds;
}
