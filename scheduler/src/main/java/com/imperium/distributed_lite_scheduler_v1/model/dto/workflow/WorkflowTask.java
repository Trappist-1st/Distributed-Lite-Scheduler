package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceRequirement;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 工作流任务节点
 * 
 * 表示工作流DAG中的一个任务节点，通过引用已定义的Task实体来复用任务配置。
 * 支持在工作流级别覆盖部分参数，提供灵活性。
 * 
 * 设计理念：
 * 1. 复用性：引用已定义的Task，避免重复配置
 * 2. 一致性：Task的修改自动生效到所有使用它的工作流
 * 3. 灵活性：通过覆盖参数支持工作流级别的定制化
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Data
public class WorkflowTask {
    
    /**
     * 节点名称（在DAG中的唯一标识，用于依赖引用）
     * 例如：data_validation, feature_extraction, model_training
     */
    @NotBlank(message = "节点名称不能为空")
    private String nodeName;
    
    /**
     * 引用的任务定义ID
     * 指向 task 表中已定义的任务，复用其配置（taskType、executorConfig、resourceRequirement等）
     */
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
    
    /**
     * 节点显示名称（可选，用于前端展示）
     * 如果为空，则使用引用Task的名称
     */
    private String displayName;
    
    /**
     * 参数覆盖（可选）
     * 在工作流级别覆盖Task定义中的参数，支持运行时动态传参
     * 例如：{"input_path": "/data/workflow_123/input", "batch_size": 100}
     * 
     * 覆盖规则：
     * - 如果Task的executorConfig中有同名参数，则覆盖
     * - 如果没有同名参数，则作为额外参数传入
     */
    private Map<String, Object> paramOverrides;
    
    /**
     * 资源需求覆盖（可选）
     * 针对此工作流的特殊资源需求，覆盖Task中的默认资源配置
     * 例如：某个数据处理任务在不同工作流中可能需要不同的内存大小
     * 
     * 如果为null，则使用Task中定义的资源需求
     */
    private ResourceRequirement resourceOverride;
    
    /**
     * 重试策略覆盖（可选）
     * 针对此工作流的特殊重试策略，覆盖Task中的默认重试配置
     * 例如：某些关键任务在特定工作流中需要更多重试次数
     * 
     * 如果为null，则使用Task中定义的重试策略
     */
    private RetryPolicy retryOverride;
    
    /**
     * 超时时间覆盖（可选，单位：秒）
     * 针对此工作流的特殊超时设置，覆盖Task中的默认超时时间
     * 
     * 如果为null，则使用Task中定义的超时时间
     */
    private Integer timeoutOverride;
    
    /**
     * 执行器配置（JSON）
     * 包含执行器的具体配置，如脚本路径、环境变量等
     */
    private Map<String, Object> executorConfig;
    
    /**
     * 优先级（可选）
     * 任务的执行优先级，范围 1-10，默认为5
     */
    private Integer priority;
}
