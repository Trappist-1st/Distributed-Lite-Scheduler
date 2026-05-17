package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流实例实体类
 * 对应表：workflow_instance
 */
@Data
@TableName("workflow_instance")
public class WorkflowInstance {
    
    /**
     * 工作流实例ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 工作流定义ID
     */
    private Long workflowId;
    
    /**
     * 所属租户ID
     */
    private Long tenantId;
    
    /**
     * 实例唯一标识
     */
    private String instanceCode;
    
    /**
     * 触发类型：MANUAL/CRON/API
     */
    private String triggerType;
    
    /**
     * 触发用户ID
     */
    private Long triggerUserId;
    
    /**
     * 状态：RUNNING/SUCCESS/FAILED/CANCELLED
     */
    private String status;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 执行时长（毫秒）
     */
    private Long durationMs;
    
    /**
     * 总任务数
     */
    private Integer totalTasks;
    
    /**
     * 成功任务数
     */
    private Integer successTasks;
    
    /**
     * 失败任务数
     */
    private Integer failedTasks;
    
    /**
     * 执行计划（JSON字符串）
     * 保存DAG执行计划的快照，用于恢复和监控
     */
    private String executionPlan;

    /**
     * P4-4 可选：实例级业务上下文（JSON 字符串），供条件表达式中 {@code context} 变量使用。
     * <p>数据库列未就绪前标记为 {@code exist = false}，避免 MyBatis 映射报错；执行 DDL 后去掉该标记并增加列 {@code context_json}。</p>
     */
    @TableField(exist = false)
    private String contextJson;

    /**
     * 失败策略：CONTINUE/STOP_ON_FAILURE
     * CONTINUE：任意任务失败继续执行其他任务
     * STOP_ON_FAILURE：任意任务失败立即停止整个工作流
     */
    private String failureStrategy;
    
    /**
     * 执行时长（秒）
     * 用于快速查询，与 durationMs 对应（durationMs / 1000）
     */
    private Integer durationSeconds;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
