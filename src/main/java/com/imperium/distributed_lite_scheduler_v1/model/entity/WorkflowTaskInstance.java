package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * 工作流任务实例实体类
 * 对应表：workflow_task_instance
 * 
 * 功能说明：
 * - 记录工作流实例中每个任务的执行状态
 * - 保存任务定义的快照（JSON格式）
 * - 跟踪任务的执行时间、重试次数等信息
 */
@Data
@Slf4j
@TableName("workflow_task_instance")
public class WorkflowTaskInstance {
    
    /**
     * 任务实例ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 工作流实例ID
     */
    private Long workflowInstanceId;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 任务定义（JSON字符串）
     * 保存WorkflowTask的快照，确保执行时定义不变
     */
    private String taskDefinition;
    
    /**
     * 执行状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED
     */
    private String status;
    
    /**
     * 所属层级索引
     * 用于确定任务在DAG中的执行顺序
     */
    private Integer layerIndex;
    
    /**
     * 重试次数
     */
    private Integer retryCount;
    
    /**
     * 关联的TaskInstance ID
     * 指向调度器中实际执行的任务实例
     */
    private Long taskInstanceId;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 执行时长（秒）
     */
    private Integer durationSeconds;
    
    /**
     * 退出码
     */
    private Integer exitCode;
    
    /**
     * 输出信息
     */
    @TableField(value = "output", jdbcType = org.apache.ibatis.type.JdbcType.LONGVARCHAR)
    private String output;
    
    /**
     * 错误信息
     */
    @TableField(value = "error_message", jdbcType = org.apache.ibatis.type.JdbcType.LONGVARCHAR)
    private String errorMessage;
    
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
    
    /**
     * 转换为TaskInstance用于提交给调度器
     * ⚠️ 重要：DAG引擎只负责编排，不做资源管理
     * 创建的TaskInstance状态为PENDING，由调度器负责：
     * - 配额检查
     * - 节点选择
     * - 资源预留
     * - 状态流转 PENDING → RUNNING
     * - 提交执行器
     * 
     * @param tenantId 租户ID
     * @param submitUserId 提交用户ID
     * @return TaskInstance 状态为PENDING的任务实例
     */
    public TaskInstance toTaskInstance(Long tenantId, Long submitUserId) {
        TaskInstance taskInstance = new TaskInstance();
        
        // 基本信息
        taskInstance.setTenantId(tenantId);
        taskInstance.setWorkflowInstanceId(this.workflowInstanceId);
        taskInstance.setSubmitUserId(submitUserId);
        taskInstance.setSubmitTime(LocalDateTime.now());
        
        // 状态初始化为PENDING，由调度器负责后续流转
        taskInstance.setStatus(TaskInstanceStatus.PENDING.getCode());
        taskInstance.setTriggerType("WORKFLOW");
        taskInstance.setPriority(5); // 默认优先级
        taskInstance.setRetryCount(0);
        
        // 解析任务定义并提取配置
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            WorkflowTask taskDef = objectMapper.readValue(this.taskDefinition, WorkflowTask.class);
            
            // 从任务定义中提取任务ID（如果有）
            if (taskDef.getTaskId() != null) {
                taskInstance.setTaskId(taskDef.getTaskId());
            }
            
            // 资源需求（JSON快照）
            if (taskDef.getResourceOverride() != null) {
                taskInstance.setResourceRequirement(
                    objectMapper.writeValueAsString(taskDef.getResourceOverride())
                );
            }
            
            // 执行器配置（JSON快照）
            if (taskDef.getExecutorConfig() != null) {
                taskInstance.setExecutorConfig(
                    objectMapper.writeValueAsString(taskDef.getExecutorConfig())
                );
            }
            
            // 任务参数（JSON快照）
            if (taskDef.getParamOverrides() != null) {
                taskInstance.setParameters(
                    objectMapper.writeValueAsString(taskDef.getParamOverrides())
                );
            }
            
            // 优先级
            if (taskDef.getPriority() != null) {
                taskInstance.setPriority(taskDef.getPriority());
            }
            
        } catch (JsonProcessingException e) {
            log.error("解析任务定义失败 taskName={}", this.taskName, e);
            // 使用默认值继续
        }
        
        return taskInstance;
    }
}
