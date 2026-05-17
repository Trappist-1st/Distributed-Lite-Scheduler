package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import com.imperium.distributed_lite_scheduler_v1.constant.FailureStrategy;
import com.imperium.distributed_lite_scheduler_v1.constant.TriggerType;
import lombok.Data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 创建工作流实例请求DTO
 * 
 * 用于接收前端或API创建工作流实例的请求参数
 */
@Data
public class WorkflowInstanceCreateRequest {
    
    /**
     * 工作流定义ID（必填）
     */
    @NotNull(message = "工作流ID不能为空")
    private Long workflowId;
    
    /**
     * 实例名称（可选）
     * 如果不提供，系统将自动生成：{工作流名称}_{时间戳}
     */
    private String instanceName;
    
    /**
     * 失败处理策略（可选）
     * 默认：STOP_ON_FAILURE
     */
    private FailureStrategy failureStrategy;
    
    /**
     * 最大并行任务数（可选）
     * 默认：10
     * 用于限制单个实例的并行度
     */
    @Min(value = 1, message = "最大并行任务数必须大于0")
    private Integer maxParallelTasks;
    
    /**
     * 触发类型（可选）
     * 默认：MANUAL
     */
    private TriggerType triggerType;
    
    /**
     * 触发用户ID（可选）
     * 如果不提供，从当前登录用户获取
     */
    private Long triggeredBy;
    
    /**
     * 是否立即执行（可选）
     * true: 创建后立即执行
     * false: 仅创建，不执行
     * 默认：true
     */
    private Boolean executeImmediately = true;

    /**
     * P4-4 可选：实例级上下文（键值）；实现后序列化写入 {@link com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance#setContextJson(String)}。
     */
    private Map<String, Object> workflowContext;
}
